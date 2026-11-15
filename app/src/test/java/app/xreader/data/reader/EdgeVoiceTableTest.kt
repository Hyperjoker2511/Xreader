package app.xreader.data.reader

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeVoiceTableTest {

    @Test
    fun `hangul sentence resolves to Korean SunHi`() {
        val text = "그는 방으로 걸어 들어갔다. 검을 뽑았다."
        assertEquals("ko-KR", EdgeVoiceTable.languageFor(text, Locale.US))
        assertEquals(
            "ko-KR-SunHiNeural",
            EdgeVoiceTable.defaultVoiceFor(text, Locale.US).id
        )
    }

    @Test
    fun `kana wins over han in mixed Japanese`() {
        // Kanji-heavy with a little kana: still Japanese — Chinese
        // text never contains kana.
        val text = "彼は部屋に入った。それから剣を抜いた。"
        assertEquals("ja-JP", EdgeVoiceTable.languageFor(text, Locale.US))
        assertEquals(
            "ja-JP-NanamiNeural",
            EdgeVoiceTable.defaultVoiceFor(text, Locale.US).id
        )
    }

    @Test
    fun `pure han resolves to Chinese`() {
        val text = "他走进了房间。然后拔出了剑。"
        assertEquals("zh-CN", EdgeVoiceTable.languageFor(text, Locale.US))
        assertEquals(
            "zh-CN-XiaoxiaoNeural",
            EdgeVoiceTable.defaultVoiceFor(text, Locale.US).id
        )
    }

    @Test
    fun `cyrillic resolves to Russian`() {
        val text = "Он вошёл в комнату и вытащил меч."
        assertEquals("ru-RU", EdgeVoiceTable.languageFor(text, Locale.US))
        assertEquals(
            "ru-RU-SvetlanaNeural",
            EdgeVoiceTable.defaultVoiceFor(text, Locale.US).id
        )
    }

    @Test
    fun `devanagari resolves to Hindi`() {
        val text = "वह कमरे में गया और तलवार निकाली।"
        assertEquals("hi-IN", EdgeVoiceTable.languageFor(text, Locale.US))
        assertEquals(
            "hi-IN-SwaraNeural",
            EdgeVoiceTable.defaultVoiceFor(text, Locale.US).id
        )
    }

    @Test
    fun `arabic resolves to Arabic`() {
        val text = "دخل الغرفة ثم سحب سيفه بهدوء"
        assertEquals("ar-SA", EdgeVoiceTable.languageFor(text, Locale.US))
        assertEquals(
            "ar-SA-ZariyahNeural",
            EdgeVoiceTable.defaultVoiceFor(text, Locale.US).id
        )
    }

    @Test
    fun `latin text follows the device locale`() {
        assertEquals(
            "fr-FR-DeniseNeural",
            EdgeVoiceTable.defaultVoiceFor("He walked in.", Locale.FRANCE).id
        )
        assertEquals(
            "de-DE-KatjaNeural",
            EdgeVoiceTable.defaultVoiceFor("He walked in.", Locale.GERMANY).id
        )
    }

    @Test
    fun `plain english resolves to Aria`() {
        val voice = EdgeVoiceTable.defaultVoiceFor("He walked in.", Locale.US)
        assertEquals("en-US", voice.language)
        assertEquals("en-US-AriaNeural", voice.id)
        assertEquals("Aria", voice.displayName)
    }

    @Test
    fun `english is first class with three curated voices`() {
        val voices = EdgeVoiceTable.voicesFor("en-US")
        assertEquals(
            listOf("en-US-AriaNeural", "en-US-GuyNeural", "en-US-JennyNeural"),
            voices.map { it.id }
        )
    }

    @Test
    fun `table integrity - sizes tags defaults and names`() {
        for (language in EdgeVoiceTable.languages) {
            val voices = EdgeVoiceTable.voicesFor(language)
            assertTrue("$language has 2-3 voices", voices.size in 2..3)
            for (voice in voices) {
                assertTrue(
                    "${voice.id} starts with its language tag",
                    voice.id.startsWith(language)
                )
                assertEquals(language, voice.language)
                assertTrue("${voice.id} has a display name", voice.displayName.isNotBlank())
            }
            // The first entry is the default, and it resolves.
            assertEquals(voices.first(), EdgeVoiceTable.defaultVoiceFor(language))
        }
    }

    @Test
    fun `a pick from another language never voices this sentence`() {
        val english = "He walked into the room and drew his sword."
        val resolved = EdgeVoiceTable.resolveVoice(english, Locale.US, "ko-KR-InJoonNeural")
        assertEquals("en-US-AriaNeural", resolved.id)
    }

    @Test
    fun `a same-language pick wins over the default`() {
        val english = "He walked into the room and drew his sword."
        val resolved = EdgeVoiceTable.resolveVoice(english, Locale.US, "en-US-GuyNeural")
        assertEquals("en-US-GuyNeural", resolved.id)
        assertEquals("Guy", resolved.displayName)

        val korean = "그는 방으로 걸어 들어갔다."
        val koResolved = EdgeVoiceTable.resolveVoice(korean, Locale.US, "ko-KR-InJoonNeural")
        assertEquals("ko-KR-InJoonNeural", koResolved.id)
    }

    @Test
    fun `unlisted language has no curated voices`() {
        assertTrue(EdgeVoiceTable.voicesFor("pt-BR").isEmpty())
        assertNull(EdgeVoiceTable.voiceById("pt-BR-CamilaNeural"))
    }
}
