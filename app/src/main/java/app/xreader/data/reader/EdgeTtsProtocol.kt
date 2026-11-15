package app.xreader.data.reader

import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone

/**
 * Pure helpers for the Microsoft Edge "read aloud" protocol, kept
 * free of Android types so they can be unit-tested on the JVM.
 *
 * Ported from wassim-ka's Xreader fork (Xreader-edge-tts,
 * EdgeTtsEngine.kt, MIT — the same licence as this tree), whose
 * protocol core was verified correct in
 * build-logs/SIFT-FORK-EDGE-TTS-2026-11-14.md. The fork's
 * script→voice picker is NOT ported: [EdgeVoiceTable] replaces
 * it with the curated table + extended script detection
 * (PATCH-SPEC-EDGE-TTS §B.4). [parseHttpDate] is the fork
 * engine's private helper, moved here so the clock-skew retry is
 * testable.
 */
object EdgeTtsProtocol {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val CHROMIUM_MAJOR_VERSION = "143"
    private const val WIN_EPOCH_SECONDS = 11_644_473_600L

    const val WS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

    /** Sec-MS-GEC token: SHA-256 of Windows-file-time ticks (rounded down
     * to 5 minutes) followed by the trusted client token, upper-case hex. */
    fun secMsGec(nowMillis: Long): String {
        var seconds = nowMillis / 1000 + WIN_EPOCH_SECONDS
        seconds -= seconds % 300
        val ticks = seconds * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun escapeXml(text: String): String = buildString(text.length + 16) {
        for (ch in text) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                // XML 1.0 forbids most control characters.
                ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> append(' ')
                else -> append(ch)
            }
        }
    }

    /** App speech rate (1.0 = normal) -> SSML prosody rate like "+25%".
     * The house rate range is 0.5..2.0, which maps exactly onto this
     * clamp's edges (-50%..+100%) — nothing the UI can produce is
     * silently squeezed (PATCH-SPEC-EDGE-TTS §B.3). */
    fun rateToProsody(rate: Float): String {
        val pct = ((rate - 1f) * 100f).toInt().coerceIn(-50, 100)
        return (if (pct >= 0) "+" else "") + pct + "%"
    }

    fun buildSsml(text: String, lang: String, voice: String, rate: Float): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='+0Hz' rate='${rateToProsody(rate)}' volume='+0%'>" +
            escapeXml(text) +
            "</prosody></voice></speak>"

    fun timestamp(nowMillis: Long): String {
        val fmt = java.text.SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(nowMillis))
    }

    fun speechConfigMessage(nowMillis: Long): String =
        "X-Timestamp:${timestamp(nowMillis)}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

    fun ssmlMessage(requestId: String, ssml: String, nowMillis: Long): String =
        "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp(nowMillis)}Z\r\n" +
            "Path:ssml\r\n\r\n" + ssml

    /** Audio payload of a binary frame: 2-byte big-endian header length,
     * the header text, then the bytes. Returns null for non-audio frames. */
    fun audioPayload(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null
        val headerLen = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val start = 2 + headerLen
        if (start > frame.size) return null
        val header = String(frame, 2, headerLen, Charsets.UTF_8)
        if (!header.contains("Path:audio")) return null
        return frame.copyOfRange(start, frame.size)
    }

    /** Parses an HTTP `Date` header (the clock-skew retry's server
     * clock). Null when the header is absent or unparseable. */
    fun parseHttpDate(value: String?): Long? {
        if (value == null) return null
        return try {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.parse(value)?.time
        } catch (_: Exception) {
            null
        }
    }
}
