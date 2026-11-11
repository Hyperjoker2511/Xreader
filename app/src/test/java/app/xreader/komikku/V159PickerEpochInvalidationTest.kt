package app.xreader.komikku

import app.xreader.domain.content.ChapterPage
import app.xreader.domain.content.ChapterPages
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.9 (E-2, Finding 5 heal rider): the one-time
 * picker-epoch invalidation. Chapters stored by the OLD
 * picker keep preview-class page URLs and the reader serves
 * stored records first — so on the first open behind the
 * epoch marker, stored image-page records of
 * declarative-source manga chapters that are pure reading
 * cache (no downloaded page) are invalidated and re-derive
 * through the fixed picker on next ensure; downloaded
 * records and prose bodies are never touched; the sweep
 * runs once. Drives the shipped seam
 * ([PickerEpochInvalidator], KomikkuReaderViewModel's own
 * logic) over a fake store, with a fake ensure that models
 * ChapterTextLoader's stored-first / fetch-and-store shape:
 * a surviving record is served as-is (no fetch); a cleared
 * one refetches — through the NEW picker, which returns the
 * original-class URLs.
 */
class V159PickerEpochInvalidationTest {

    /** One stored chapter body in the fake store. */
    private class FakeRecord(
        var pages: ChapterPages?,
        val hasProse: Boolean = false,
        val rowDownloaded: Boolean = false,
        val eligibleSource: Boolean = true
    ) {
        var present: Boolean = true
    }

    private class FakeStore {
        var epoch: Int = 0
        val records = mutableMapOf<Long, FakeRecord>()
        var invalidations: Int = 0
        var enumerations: Int = 0
        var fetches: Int = 0

        /** The corrected picker's answer for a re-derived
         * chapter: original-class URLs. */
        private fun freshPages(): ChapterPages = ChapterPages(
            pages = listOf(
                ChapterPage("https://fenrir.example/pages/1-original.jpg"),
                ChapterPage("https://fenrir.example/pages/2-original.jpg")
            )
        )

        fun invalidator(): PickerEpochInvalidator = PickerEpochInvalidator(
            readEpoch = { epoch },
            writeEpoch = { epoch = it },
            candidates = {
                enumerations++
                records.map { (chapterId, record) ->
                    PickerEpochInvalidator.Candidate(
                        bookId = 7L,
                        chapterId = chapterId,
                        eligibleSource = record.eligibleSource,
                        hasBody = record.present,
                        pages = if (record.present) record.pages else null,
                        hasProse = record.hasProse,
                        rowDownloaded = record.rowDownloaded
                    )
                }
            },
            invalidate = { candidate ->
                invalidations++
                val record = records.getValue(candidate.chapterId)
                if (record.hasProse) {
                    // The production mixed-record branch:
                    // drop ONLY the page record, prose stays.
                    record.pages = null
                } else {
                    record.present = false
                    record.pages = null
                }
            }
        )

        /** ChapterTextLoader-shaped ensure: stored record
         * first; a missing record refetches and stores. */
        fun ensure(chapterId: Long): ChapterPages? {
            val record = records.getValue(chapterId)
            if (record.present && record.pages != null) return record.pages
            fetches++
            val fresh = freshPages()
            record.present = true
            record.pages = fresh
            return fresh
        }
    }

    private fun previewPages(localFileOfFirst: String? = null) = ChapterPages(
        pages = listOf(
            ChapterPage(
                "https://fenrir.example/pages/1-preview.jpg",
                localFile = localFileOfFirst
            ),
            ChapterPage("https://fenrir.example/pages/2-preview.jpg")
        )
    )

    @Test
    fun `stale preview record is cleared and the next ensure refetches the original`() =
        runTest {
            val store = FakeStore()
            store.records[42L] = FakeRecord(pages = previewPages())

            // Before the sweep, stored-first serving returns
            // the preview record untouched (the defect).
            assertEquals(
                "https://fenrir.example/pages/1-preview.jpg",
                store.ensure(42L)!!.pages[0].url
            )
            assertEquals(0, store.fetches)

            val cleared = store.invalidator().runIfNeeded()
            assertEquals(1, cleared)
            assertEquals(PickerEpoch.CURRENT, store.epoch)
            assertFalse(
                "the stale record must be gone after the sweep",
                store.records.getValue(42L).present
            )

            // The observable heal: the next ensure REFETCHES
            // instead of serving the stored preview record,
            // and the re-derived record carries the original.
            val healed = store.ensure(42L)
            assertEquals(1, store.fetches)
            assertEquals(
                "https://fenrir.example/pages/1-original.jpg",
                healed!!.pages[0].url
            )
        }

    @Test
    fun `a record with a downloaded page survives the epoch bump`() = runTest {
        val store = FakeStore()
        store.records[42L] = FakeRecord(
            pages = previewPages(localFileOfFirst = "img-pabcd-001.jpg")
        )
        val cleared = store.invalidator().runIfNeeded()
        assertEquals(0, cleared)
        assertEquals(0, store.invalidations)
        assertEquals(PickerEpoch.CURRENT, store.epoch)
        // Still served stored-first — no refetch, file intact.
        val served = store.ensure(42L)
        assertEquals(0, store.fetches)
        assertEquals(
            "img-pabcd-001.jpg", served!!.pages[0].localFile
        )
    }

    @Test
    fun `a user-downloaded row survives even with no page files yet`() = runTest {
        val store = FakeStore()
        store.records[42L] = FakeRecord(
            pages = previewPages(), rowDownloaded = true
        )
        assertEquals(0, store.invalidator().runIfNeeded())
        assertTrue(store.records.getValue(42L).present)
    }

    @Test
    fun `a prose body survives the epoch bump`() = runTest {
        val store = FakeStore()
        store.records[42L] = FakeRecord(pages = null, hasProse = true)
        val cleared = store.invalidator().runIfNeeded()
        assertEquals(0, cleared)
        assertEquals(0, store.invalidations)
        assertTrue(
            "the prose body must still be stored",
            store.records.getValue(42L).present
        )
        assertEquals(PickerEpoch.CURRENT, store.epoch)
    }

    @Test
    fun `a player source record is never a candidate for clearing`() = runTest {
        val store = FakeStore()
        store.records[42L] = FakeRecord(
            pages = previewPages(), eligibleSource = false
        )
        assertEquals(0, store.invalidator().runIfNeeded())
        assertTrue(store.records.getValue(42L).present)
        assertEquals(0, store.fetches)
    }

    @Test
    fun `second run at the current epoch performs no clears`() = runTest {
        val store = FakeStore()
        store.records[42L] = FakeRecord(pages = previewPages())
        val invalidator = store.invalidator()
        assertEquals(1, invalidator.runIfNeeded())
        assertEquals(1, store.invalidations)
        assertEquals(1, store.enumerations)

        // A fresh record derived by the new picker now sits
        // in the store; the second run must not touch it —
        // it does not even enumerate.
        store.records[43L] = FakeRecord(pages = previewPages())
        assertEquals(0, invalidator.runIfNeeded())
        assertEquals(1, store.invalidations)
        assertEquals(1, store.enumerations)
        assertTrue(store.records.getValue(43L).present)
    }

    @Test
    fun `marker advances even when nothing needs clearing`() = runTest {
        val store = FakeStore()
        assertEquals(0, store.invalidator().runIfNeeded())
        assertEquals(PickerEpoch.CURRENT, store.epoch)
    }
}
