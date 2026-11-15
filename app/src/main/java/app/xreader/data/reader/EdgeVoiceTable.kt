package app.xreader.data.reader

import java.util.Locale

/**
 * The Edge online voices (Patch 12): which language a sentence is
 * in (by the script of its letters) and the curated neural voices
 * for that language — 2–3 per language, the first entry the
 * language's default. Pure, so the table and the script rules are
 * JVM-tested.
 *
 * Script detection extends the fork's counters (Han / Arabic /
 * Latin) with Hangul, Kana, Cyrillic and Devanagari. Kana is
 * checked FIRST: Japanese mixes kanji with kana, Chinese never
 * has kana, so any kana means Japanese even when Han dominates.
 * The fork's win threshold is kept — a script wins when it is at
 * least a third of the counted letters against Latin
 * (`script * 3 >= latin`).
 *
 * Voice ids marked † in PATCH-SPEC-EDGE-TTS §B.4 were proven
 * against the live service by the fork's author; the rest are
 * Microsoft catalogue ids, unverified live from the build
 * machine (the endpoint refuses datacenter IPs) — a wrong id
 * fails as a synthesis error and the router falls back to the
 * device voice, never silence.
 */
object EdgeVoiceTable {

    data class Voice(val id: String, val displayName: String, val language: String)

    private val VOICES: Map<String, List<Voice>> = listOf(
        Voice("en-US-AriaNeural", "Aria", "en-US"),
        Voice("en-US-GuyNeural", "Guy", "en-US"),
        Voice("en-US-JennyNeural", "Jenny", "en-US"),
        Voice("ko-KR-SunHiNeural", "SunHi", "ko-KR"),
        Voice("ko-KR-InJoonNeural", "InJoon", "ko-KR"),
        Voice("ja-JP-NanamiNeural", "Nanami", "ja-JP"),
        Voice("ja-JP-KeitaNeural", "Keita", "ja-JP"),
        Voice("ru-RU-SvetlanaNeural", "Svetlana", "ru-RU"),
        Voice("ru-RU-DmitryNeural", "Dmitry", "ru-RU"),
        Voice("hi-IN-SwaraNeural", "Swara", "hi-IN"),
        Voice("hi-IN-MadhurNeural", "Madhur", "hi-IN"),
        Voice("zh-CN-XiaoxiaoNeural", "Xiaoxiao", "zh-CN"),
        Voice("zh-CN-YunyeNeural", "Yunye", "zh-CN"),
        Voice("ar-SA-ZariyahNeural", "Zariyah", "ar-SA"),
        Voice("ar-SA-HamedNeural", "Hamed", "ar-SA"),
        Voice("fr-FR-DeniseNeural", "Denise", "fr-FR"),
        Voice("fr-FR-HenriNeural", "Henri", "fr-FR"),
        Voice("es-ES-ElviraNeural", "Elvira", "es-ES"),
        Voice("es-ES-AlvaroNeural", "Alvaro", "es-ES"),
        Voice("de-DE-KatjaNeural", "Katja", "de-DE"),
        Voice("de-DE-ConradNeural", "Conrad", "de-DE")
    ).groupBy { it.language }

    /** Every language tag the table lists voices for. */
    val languages: Set<String> get() = VOICES.keys

    /** The detected language tag for a sentence, e.g. "ko-KR". */
    fun languageFor(text: String, deviceLocale: Locale): String {
        var han = 0
        var arabic = 0
        var hangul = 0
        var kana = 0
        var cyrillic = 0
        var devanagari = 0
        var latin = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0x3040..0x30FF -> kana++
                c in 0xAC00..0xD7A3 || c in 0x1100..0x11FF -> hangul++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> han++
                c in 0x0600..0x06FF -> arabic++
                c in 0x0400..0x04FF -> cyrillic++
                c in 0x0900..0x097F -> devanagari++
                ch.isLetter() -> latin++
            }
        }
        return when {
            kana > 0 -> "ja-JP"
            han > 0 && han >= arabic && han * 3 >= latin -> "zh-CN"
            arabic > 0 && arabic * 3 >= latin -> "ar-SA"
            hangul > 0 && hangul * 3 >= latin -> "ko-KR"
            cyrillic > 0 && cyrillic * 3 >= latin -> "ru-RU"
            devanagari > 0 && devanagari * 3 >= latin -> "hi-IN"
            else -> when (deviceLocale.language) {
                "fr" -> "fr-FR"
                "es" -> "es-ES"
                "de" -> "de-DE"
                "ko" -> "ko-KR"
                "ja" -> "ja-JP"
                "ru" -> "ru-RU"
                "hi" -> "hi-IN"
                "zh" -> "zh-CN"
                "ar" -> "ar-SA"
                else -> "en-US"
            }
        }
    }

    /** Curated voices for a language tag (empty = unlisted). */
    fun voicesFor(language: String): List<Voice> = VOICES[language].orEmpty()

    /** The table default (first curated voice) for a language
     * tag; unlisted tags resolve to the English default so a
     * voice is always returned. */
    fun defaultVoiceFor(language: String): Voice =
        VOICES[language]?.firstOrNull() ?: VOICES.getValue("en-US").first()

    /** Default voice for a sentence (override applied by caller). */
    fun defaultVoiceFor(text: String, deviceLocale: Locale): Voice =
        defaultVoiceFor(languageFor(text, deviceLocale))

    /** The curated voice with this id, or null when unlisted. */
    fun voiceById(id: String): Voice? =
        VOICES.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }

    /**
     * Resolves the voice for one sentence: the caller's explicit
     * pick wins ONLY when its language equals the sentence's
     * detected language (voice ids begin with their language
     * tag, so the check is a prefix comparison) — a Korean pick
     * never voices an English chapter. Otherwise the table
     * default for the detected language speaks.
     */
    fun resolveVoice(text: String, deviceLocale: Locale, overrideVoiceId: String?): Voice {
        val language = languageFor(text, deviceLocale)
        if (overrideVoiceId != null && overrideVoiceId.startsWith(language)) {
            voiceById(overrideVoiceId)?.let { return it }
            // A same-language id the curated table doesn't list
            // (a stale pick): trust the id itself.
            return Voice(
                id = overrideVoiceId,
                displayName = displayNameOf(overrideVoiceId),
                language = language
            )
        }
        return defaultVoiceFor(language)
    }

    /** "en-US-AriaNeural" -> "Aria" (the status line's name). */
    fun displayNameOf(voiceId: String): String =
        voiceId.substringAfterLast('-').removeSuffix("Neural")
            .ifBlank { voiceId }
}
