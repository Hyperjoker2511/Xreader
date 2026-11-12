package app.xreader.ui.reader

import app.xreader.data.sync.ReaderPrefsCodec
import app.xreader.domain.reader.ParagraphSpacing
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderValidation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.41 gate: the phone-proof fix round.
 *
 *  - Fix 1 census: the colour wheel's door is the shared
 *    full-width "Pick exact colour" button in BOTH reader
 *    sheets — both doors (page + words / text + page) — and
 *    the old hidden entries (novel text links, EPUB chips)
 *    are gone.
 *  - Fix 2 census: IdentityCard3D carries no radial-gradient
 *    decoration and no stage canvas; its only light is the
 *    linear sheen, and the tilt feed has the warm-up seeding
 *    that keeps the resting card flat from the first frame.
 *  - Item 3 mapping pins: the paragraph-spacing presets map
 *    to EXACTLY the stored dp values they rendered before the
 *    exact-gap slider arrived (storage was always a plain dp
 *    float — these pins prove the widening changed no user's
 *    rendered gap), through validation AND the sync codec,
 *    for presets and for a custom value alike. Same for the
 *    TTS speed presets.
 */
class V1541SheetAuditTest {

    // ── source census helpers (house idiom) ─────────────────

    private fun sourceFile(pathFromMain: String): File {
        val candidates = listOf(
            File("src/main/java/app/xreader/$pathFromMain"),
            File("app/src/main/java/app/xreader/$pathFromMain")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("source file not found: $pathFromMain")
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    // ── Fix 1: the wheel's doors ────────────────────────────

    @Test
    fun `novel sheet opens the wheel through two full width buttons`() {
        val source = sourceFile("ui/reader/ReaderSheets.kt").readText()
        assertEquals(
            2, occurrences(source, "PickExactColourButton(")
        )
        assertFalse(source.contains("Pick the page colour exactly"))
        assertFalse(source.contains("Pick the word colour exactly"))
        // Both doors drive the same wheel state the links did.
        assertTrue(source.contains("onClick = { wheelFor = \"page\" }"))
        assertTrue(source.contains("onClick = { wheelFor = \"words\" }"))
    }

    @Test
    fun `epub sheet opens the wheel through two full width buttons`() {
        val source = sourceFile("ui/localreader/EpubBookReader.kt").readText()
        assertEquals(
            2, occurrences(source, "PickExactColourButton(")
        )
        assertFalse(source.contains("Text(\"Exact colour…\")"))
        assertTrue(source.contains("onClick = { wheelFor = \"text\" }"))
        assertTrue(source.contains("onClick = { wheelFor = \"page\" }"))
    }

    @Test
    fun `the shared button carries the plain label`() {
        val source = sourceFile("ui/common/ColourWheelPicker.kt").readText()
        assertTrue(source.contains("\"Pick exact colour\""))
        assertTrue(source.contains("fun PickExactColourButton("))
    }

    // ── Fix 2: one consistent card shape ────────────────────

    @Test
    fun `identity card has no circular glow and a flat rest`() {
        val source = sourceFile("ui/profile/MeShowpiece.kt").readText()
        val card = source.substringAfter("fun IdentityCard3D")
        assertFalse(
            "no radial gradient anywhere in the card",
            card.contains("radialGradient")
        )
        assertFalse(card.contains("Ember Stage"))
        // The only light left is the linear sheen.
        assertTrue(card.contains("Brush.linearGradient"))
        // Warm-up seeding: the baseline adopts the held angle
        // before any lean can be computed.
        assertTrue(card.contains("tiltReadings"))
        assertTrue(card.contains("tiltBaseline = deviceTilt"))
    }

    // ── Item 3: preset mapping pins ─────────────────────────

    @Test
    fun `paragraph spacing presets keep their exact stored dp`() {
        assertEquals(4f, ParagraphSpacing.COMPACT.dp, 0f)
        assertEquals(10f, ParagraphSpacing.NORMAL.dp, 0f)
        assertEquals(18f, ParagraphSpacing.RELAXED.dp, 0f)
        // The fresh-install default is the NORMAL stop itself.
        assertEquals(
            ParagraphSpacing.NORMAL.dp,
            ReaderConfig().paragraphSpacingDp,
            0f
        )
    }

    @Test
    fun `paragraph spacing exact match separates presets from custom`() {
        assertEquals(
            ParagraphSpacing.COMPACT, ParagraphSpacing.exact(4f)
        )
        assertEquals(
            ParagraphSpacing.NORMAL, ParagraphSpacing.exact(10f)
        )
        assertEquals(
            ParagraphSpacing.RELAXED, ParagraphSpacing.exact(18f)
        )
        assertNull(ParagraphSpacing.exact(14f))
        assertNull(ParagraphSpacing.exact(10.5f))
        assertNull(ParagraphSpacing.exact(0f))
        // The slider's whole range is inside the validated
        // range, presets included.
        for (preset in ParagraphSpacing.entries) {
            assertTrue(
                preset.dp in
                    ReaderValidation.PARAGRAPH_SPACING_RANGE
            )
        }
    }

    @Test
    fun `stored paragraph gaps survive validation and sync unchanged`() {
        val values = ParagraphSpacing.entries.map { it.dp } +
            listOf(14f, 7.5f, 0f, 32f)
        for (dp in values) {
            val config = ReaderConfig(paragraphSpacingDp = dp)
            assertEquals(
                "validation must not move $dp",
                dp,
                ReaderValidation.validate(config).paragraphSpacingDp,
                0f
            )
            val roundTripped = ReaderPrefsCodec.decode(
                ReaderPrefsCodec.encode(config), ReaderConfig()
            )
            assertEquals(
                "sync round trip must not move $dp",
                dp,
                roundTripped.paragraphSpacingDp,
                0f
            )
        }
    }

    // ── Item 3: the other uncapped quantities ───────────────

    @Test
    fun `tts speed presets sit inside the validated range unchanged`() {
        for (rate in listOf(0.75f, 1f, 1.25f, 1.5f, 2f)) {
            assertTrue(rate in ReaderValidation.SPEECH_RATE_RANGE)
            assertEquals(
                rate,
                ReaderValidation.validate(
                    ReaderConfig(speechRate = rate)
                ).speechRate,
                0f
            )
        }
    }

    @Test
    fun `listen sheet speed and sleep carry exact sliders`() {
        val source = sourceFile("ui/reader/ReaderSheets.kt").readText()
        assertTrue(source.contains("onRateChange(v)"))
        assertTrue(
            source.contains("SleepMode.AfterMinutes(v.roundToInt())")
        )
        // Paragraph slider writes the same stored float.
        assertTrue(source.contains("paragraphSpacingDp = v"))
        assertTrue(
            source.contains(".exact(config.paragraphSpacingDp)")
        )
    }
}
