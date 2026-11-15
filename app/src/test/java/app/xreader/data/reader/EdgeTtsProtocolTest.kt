package app.xreader.data.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fork's five protocol tests (Xreader-edge-tts, MIT), plus the
 * spec's pinned Sec-MS-GEC known-answer vector and the wire-message
 * shapes. The fork's script→voice test is NOT here: voice picking
 * moved to [EdgeVoiceTable] (see EdgeVoiceTableTest).
 */
class EdgeTtsProtocolTest {

    @Test
    fun `secMsGec is stable inside a five minute window and upper-case sha256`() {
        val a = EdgeTtsProtocol.secMsGec(1_700_000_000_000L)
        val b = EdgeTtsProtocol.secMsGec(1_700_000_000_000L + 60_000L)
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'A'..'F' })
    }

    @Test
    fun `secMsGec matches the pinned known-answer vector`() {
        // PATCH-SPEC-EDGE-TTS §B.1: ticks 133444734000000000 for
        // this instant, SHA-256 of "<ticks><trusted token>".
        assertEquals(
            "42301B335578FEFDAE2637DED1ABD614505D432559EC08032B82048483726AFF",
            EdgeTtsProtocol.secMsGec(1_700_000_000_000L)
        )
    }

    @Test
    fun `xml special characters are escaped`() {
        assertEquals(
            "a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;",
            EdgeTtsProtocol.escapeXml("a & b <c> \"d\" 'e'")
        )
    }

    @Test
    fun `xml control characters become spaces`() {
        assertEquals("a b", EdgeTtsProtocol.escapeXml("a\u0001b"))
        // Newlines and tabs are legal XML and pass through.
        assertEquals("a\nb\tc", EdgeTtsProtocol.escapeXml("a\nb\tc"))
    }

    @Test
    fun `rate maps to a clamped prosody percentage`() {
        assertEquals("+0%", EdgeTtsProtocol.rateToProsody(1f))
        assertEquals("+50%", EdgeTtsProtocol.rateToProsody(1.5f))
        assertEquals("-25%", EdgeTtsProtocol.rateToProsody(0.75f))
        assertEquals("+100%", EdgeTtsProtocol.rateToProsody(5f))
        assertEquals("-50%", EdgeTtsProtocol.rateToProsody(0.1f))
        // The house range edges (0.5x / 2.0x) land exactly on the clamp.
        assertEquals("-50%", EdgeTtsProtocol.rateToProsody(0.5f))
        assertEquals("+100%", EdgeTtsProtocol.rateToProsody(2f))
    }

    @Test
    fun `audio payload is read after the binary header and other frames are ignored`() {
        val header = "X-RequestId:1\r\nPath:audio\r\n".toByteArray()
        val frame = byteArrayOf((header.size shr 8).toByte(), header.size.toByte()) +
            header + byteArrayOf(1, 2, 3)
        assertEquals(listOf<Byte>(1, 2, 3), EdgeTtsProtocol.audioPayload(frame)!!.toList())

        val other = "Path:turn.start".toByteArray()
        val otherFrame = byteArrayOf((other.size shr 8).toByte(), other.size.toByte()) + other
        assertNull(EdgeTtsProtocol.audioPayload(otherFrame))
        assertNotNull(EdgeTtsProtocol.buildSsml("Hi", "en-US", "en-US-AriaNeural", 1f))
    }

    @Test
    fun `ssml carries lang voice and prosody`() {
        val ssml = EdgeTtsProtocol.buildSsml("Hi <there>", "ko-KR", "ko-KR-SunHiNeural", 1.25f)
        assertTrue(ssml.contains("xml:lang='ko-KR'"))
        assertTrue(ssml.contains("<voice name='ko-KR-SunHiNeural'>"))
        assertTrue(ssml.contains("rate='+25%'"))
        assertTrue(ssml.contains("Hi &lt;there&gt;"))
    }

    @Test
    fun `speech config message shape`() {
        val message = EdgeTtsProtocol.speechConfigMessage(1_700_000_000_000L)
        assertTrue(message.startsWith("X-Timestamp:"))
        assertTrue(message.contains("Content-Type:application/json; charset=utf-8"))
        assertTrue(message.contains("Path:speech.config"))
        assertTrue(message.contains("\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\""))
    }

    @Test
    fun `ssml message shape`() {
        val message = EdgeTtsProtocol.ssmlMessage("abc123", "<speak/>", 1_700_000_000_000L)
        assertTrue(message.startsWith("X-RequestId:abc123\r\n"))
        assertTrue(message.contains("Content-Type:application/ssml+xml"))
        assertTrue(message.contains("Path:ssml"))
        assertTrue(message.endsWith("<speak/>"))
    }

    @Test
    fun `http date parses for the clock-skew retry`() {
        // Tue, 14 Nov 2023 22:13:20 GMT == 1_700_000_000_000 ms.
        assertEquals(
            1_700_000_000_000L,
            EdgeTtsProtocol.parseHttpDate("Tue, 14 Nov 2023 22:13:20 GMT")
        )
        assertNull(EdgeTtsProtocol.parseHttpDate(null))
        assertNull(EdgeTtsProtocol.parseHttpDate("not a date"))
    }
}
