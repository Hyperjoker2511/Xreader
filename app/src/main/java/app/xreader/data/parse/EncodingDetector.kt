package app.xreader.data.parse

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Text-encoding detection for TXT/FB2-era files (PLAN §4): BOMs first,
 * then strict-decode trials over a sample. The candidate order matters
 * for CJK: UTF-8 is unambiguous; among the legacy encodings we score by
 * (decode success, share of plausibly-text characters, CJK ideographs)
 * so a GBK novel doesn't get claimed by Shift_JIS just because both
 * happen to decode.
 *
 * Pure JVM — the detector is exercised directly by fixture tests.
 */
object EncodingDetector {

    private const val SAMPLE_BYTES = 64 * 1024

    /** Legacy candidates in trial order (after UTF-8). */
    private val LEGACY_CANDIDATES: List<Charset> = listOf(
        charsetOrNull("GBK"),
        charsetOrNull("Big5"),
        charsetOrNull("Shift_JIS"),
        charsetOrNull("EUC-KR"),
        charsetOrNull("windows-1252")
    ).filterNotNull()

    fun detect(file: File): Charset =
        file.inputStream().buffered().use { detect(it) }

    fun detect(input: InputStream): Charset {
        val sample = ByteArray(SAMPLE_BYTES)
        var read = 0
        while (read < sample.size) {
            val n = input.read(sample, read, sample.size - read)
            if (n < 0) break
            read += n
        }
        return detectBytes(sample.copyOf(read))
    }

    fun detectBytes(sample: ByteArray): Charset {
        if (sample.isEmpty()) return Charsets.UTF_8
        // BOMs are unambiguous.
        when {
            sample.size >= 3 && sample[0] == 0xEF.toByte() &&
                sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte() ->
                return Charsets.UTF_8
            sample.size >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() ->
                return Charsets.UTF_16LE
            sample.size >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte() ->
                return Charsets.UTF_16BE
        }
        // Pure ASCII is UTF-8 for our purposes.
        if (sample.all { it.toInt() and 0xFF < 0x80 }) return Charsets.UTF_8
        // Strict UTF-8 wins whenever it truly decodes.
        if (tryDecode(sample, Charsets.UTF_8) != null) return Charsets.UTF_8

        // Legacy CJK encodings all decode each other's bytes into
        // *something* — the discriminator is character frequency: a
        // correct decode is dense in the language's most common
        // characters （的/は/은…), a wrong decode scatters into rare
        // codepoints. Strict decoding first (invalid byte pairs
        // exclude a candidate outright), then frequency scoring.
        val decoded = LEGACY_CANDIDATES.mapNotNull { candidate ->
            tryDecode(sample, candidate)?.let { candidate to it }
        }
        if (decoded.isEmpty()) return Charsets.UTF_8

        var best: Charset? = null
        var bestScore = -1.0
        for ((candidate, text) in decoded) {
            val score = scoreDecoded(text)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best ?: Charsets.UTF_8
    }

    /** Strict decode; null when the bytes are not valid in [charset].
     * A truncated multi-byte char at the very end of the sample is
     * tolerated (the sample is a prefix, not the whole file). */
    private fun tryDecode(bytes: ByteArray, charset: Charset): String? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // Retry without the last 3 bytes: the sample may end mid-char.
            if (bytes.size > 4) {
                try {
                    decoder.reset()
                    decoder.decode(ByteBuffer.wrap(bytes, 0, bytes.size - 3)).toString()
                } catch (e2: Exception) {
                    null
                }
            } else {
                null
            }
        }
    }

    /**
     * Scores a candidate decode: printable ratio, plus a strong bonus
     * for density of the most common Chinese/Japanese/Korean
     * characters — real prose is dense in them, wrong-encoding garbage
     * is not.
     */
    private fun scoreDecoded(text: String): Double {
        if (text.isEmpty()) return -1.0
        var good = 0
        var common = 0
        var script = 0
        var bad = 0
        for (ch in text) {
            when {
                ch == '�' -> bad += 4
                ch.isISOControl() && ch !in "\n\r\t" -> bad += 2
                else -> {
                    good++
                    if (isCjk(ch)) script++
                    if (ch in COMMON_CHARS) common++
                }
            }
        }
        val total = good + bad
        if (total == 0) return -1.0
        val printableRatio = good.toDouble() / total
        val commonShare = common.toDouble() / text.length
        val scriptShare = script.toDouble() / text.length
        return printableRatio + 2.0 * commonShare + 0.25 * scriptShare
    }

    /** The highest-frequency characters of the library's languages.
     * A correct decode of real prose hits these constantly. */
    private val COMMON_CHARS: Set<Char> = (
        // Chinese (simplified + traditional commons)
        "的一是不了人我在有他这中大来上国个到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可她里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见经头面公同三已老从动两长知民样现分将外但身些与月几音力中文這麼們國學來時個麼這裡後麼麼麽" +
            // Japanese hiragana + common katakana/kanji particles
            "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをんがぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽぁぃぅぇぉゃゅょっー" +
            "日月火水木金土年時分秒円万億" +
            // Korean most frequent syllables
            "은는이가을를에의로하도고와과만지다서어고니아자요것수있없같들때문해져라니면듯함께"
        ).toSet()

    private fun isCjk(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    private fun isKana(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
            (ch in 'ｱ'..'ﾝ') // halfwidth katakana
    }

    private fun isHangul(ch: Char): Boolean =
        Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.HANGUL_SYLLABLES

    private fun charsetOrNull(name: String): Charset? =
        runCatching { Charset.forName(name) }.getOrNull()
}
