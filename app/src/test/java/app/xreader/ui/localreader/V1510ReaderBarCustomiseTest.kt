package app.xreader.ui.localreader

import app.xreader.fixtures.FakePreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.10 (F1) gate: the customisable reader bar's store.
 *
 * Tree truth (map F1): the untouched store renders each
 * reader's SHIPPED row natively — EPUB's six actions, PDF's
 * different four. Only the user's first customisation creates
 * ONE shared config; per-format availability is named and
 * enforced (Font Size is EPUB-only in the bar).
 */
class V1510ReaderBarCustomiseTest {

    // ── Defaults: untouched store renders natives ───────────

    @Test
    fun `untouched store gives EPUB its shipped six`() {
        assertEquals(
            listOf(
                ReaderBarActionId.DAY_NIGHT,
                ReaderBarActionId.CHAPTERS,
                ReaderBarActionId.BOOKMARKS,
                ReaderBarActionId.SEARCH,
                ReaderBarActionId.VISUAL_OPTIONS,
                ReaderBarActionId.SPEAK
            ),
            resolveReaderBar(false, null, ReaderFormat.EPUB)
        )
    }

    @Test
    fun `untouched store gives PDF its shipped four`() {
        assertEquals(
            listOf(
                ReaderBarActionId.BOOKMARK_ADD,
                ReaderBarActionId.BOOKMARKS,
                ReaderBarActionId.TEXT_VIEW,
                ReaderBarActionId.VISUAL_OPTIONS
            ),
            resolveReaderBar(false, null, ReaderFormat.PDF)
        )
    }

    @Test
    fun `store defaults - not customised, no config`() = runTest {
        val store = ReaderBarStore(FakePreferencesDataStore())
        assertFalse(store.customized.first())
        assertNull(store.config.first())
    }

    // ── Availability: per-format, named ─────────────────────

    @Test
    fun `font size is EPUB-only and named so`() {
        assertTrue(ReaderBarActionId.FONT_SIZE.epubAvailable)
        assertFalse(ReaderBarActionId.FONT_SIZE.pdfAvailable)
        assertEquals("EPUB only", ReaderBarActionId.FONT_SIZE.availabilityNote)
    }

    @Test
    fun `pdf-only actions are named too`() {
        assertEquals("PDF only", ReaderBarActionId.BOOKMARK_ADD.availabilityNote)
        assertEquals("PDF only", ReaderBarActionId.TEXT_VIEW.availabilityNote)
        assertFalse(ReaderBarActionId.BOOKMARK_ADD.epubAvailable)
        assertFalse(ReaderBarActionId.TEXT_VIEW.epubAvailable)
    }

    @Test
    fun `custom config applies per format availability`() {
        val config = ReaderBarConfig.seededFrom(ReaderFormat.EPUB).copy(
            orderedKeys = listOf(
                ReaderBarActionId.FONT_SIZE.key,
                ReaderBarActionId.TEXT_VIEW.key,
                ReaderBarActionId.SEARCH.key
            ),
            enabledKeys = setOf(
                ReaderBarActionId.FONT_SIZE.key,
                ReaderBarActionId.TEXT_VIEW.key,
                ReaderBarActionId.SEARCH.key
            )
        )
        assertEquals(
            listOf(
                ReaderBarActionId.FONT_SIZE,
                ReaderBarActionId.SEARCH
            ),
            resolveReaderBar(true, config, ReaderFormat.EPUB)
        )
        assertEquals(
            listOf(
                ReaderBarActionId.TEXT_VIEW,
                ReaderBarActionId.SEARCH
            ),
            resolveReaderBar(true, config, ReaderFormat.PDF)
        )
    }

    // ── Seeding + edits ─────────────────────────────────────

    @Test
    fun `seed from PDF starts at the shipped four`() {
        val seeded = ReaderBarConfig.seededFrom(ReaderFormat.PDF)
        assertEquals(
            listOf(
                ReaderBarActionId.BOOKMARK_ADD,
                ReaderBarActionId.BOOKMARKS,
                ReaderBarActionId.TEXT_VIEW,
                ReaderBarActionId.VISUAL_OPTIONS
            ),
            seeded.activeActions(ReaderFormat.PDF)
        )
    }

    @Test
    fun `toggle enables in order and removes cleanly`() {
        val base = ReaderBarConfig.seededFrom(ReaderFormat.PDF)
        val added = base.toggled(ReaderBarActionId.SEARCH, enabled = true)
        // Active actions render in the config's order — the
        // shipped row leads (seed order), newly enabled
        // actions follow in the Moon enumeration.
        assertEquals(
            listOf(
                ReaderBarActionId.BOOKMARK_ADD,
                ReaderBarActionId.BOOKMARKS,
                ReaderBarActionId.TEXT_VIEW,
                ReaderBarActionId.VISUAL_OPTIONS,
                ReaderBarActionId.SEARCH
            ),
            added.activeActions(ReaderFormat.PDF)
        )
        val removed = added.toggled(ReaderBarActionId.SEARCH, enabled = false)
        assertEquals(
            base.activeActions(ReaderFormat.PDF).toSet(),
            removed.activeActions(ReaderFormat.PDF).toSet()
        )
    }

    @Test
    fun `move reorders by index and out of range is a no-op`() {
        val base = ReaderBarConfig.seededFrom(ReaderFormat.PDF)
        // Seeded order leads with the shipped PDF row.
        assertEquals(
            listOf("bookmarkAdd", "bookmarks", "textView", "visualOptions"),
            base.orderedKeys.take(4)
        )
        val moved = base.moved(fromIndex = 1, toIndex = 2)
        assertEquals(
            listOf("bookmarkAdd", "textView", "bookmarks", "visualOptions"),
            moved.orderedKeys.take(4)
        )
        assertEquals(base.orderedKeys, base.moved(0, -1).orderedKeys)
        assertEquals(base.orderedKeys, base.moved(-1, 0).orderedKeys)
        assertEquals(base.orderedKeys, base.moved(0, 99).orderedKeys)
        assertEquals(base.orderedKeys, base.moved(3, 3).orderedKeys)
    }

    // ── Codec + store round trip ────────────────────────────

    @Test
    fun `codec round trips and corrupt input is null`() {
        val config = ReaderBarConfig.seededFrom(ReaderFormat.EPUB)
            .copy(doubleLineLayout = true)
        assertEquals(config, decodeReaderBarConfig(encodeReaderBarConfig(config)))
        assertNull(decodeReaderBarConfig("garbage"))
        assertNull(decodeReaderBarConfig(null))
        assertNull(decodeReaderBarConfig(""))
    }

    @Test
    fun `first save flips customised and persists`() = runTest {
        val store = ReaderBarStore(FakePreferencesDataStore())
        val config = ReaderBarConfig.seededFrom(ReaderFormat.PDF)
            .copy(doubleLineLayout = true)
        store.saveConfig(config)
        assertTrue(store.customized.first())
        assertEquals(config, store.config.first())
        assertEquals(
            config.activeActions(ReaderFormat.EPUB),
            resolveReaderBar(true, store.config.first(), ReaderFormat.EPUB)
        )
    }
}
