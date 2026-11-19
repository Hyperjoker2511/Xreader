package app.xreader.data.prepare

import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.Chapter
import app.xreader.data.db.SourceType
import app.xreader.data.library.ReadingMemoryCodec
import app.xreader.ui.localreader.EpubPrepareRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 Patch 3 (Item 6): the shared routing rules —
 * EpubPrepareAccess.isPrepared (the one validity rule behind
 * Item 2a's resolver seam, the local open flow and the book
 * page) and the Contents door's translation (Item 2e). The
 * resolver verdicts themselves are pinned in
 * ReaderRouteResolverTest's Patch 3 additions.
 */
class Patch3RoutingTest {

    private val sha = "a".repeat(64)

    private fun book(fingerprint: String? = "name|10|$sha") = Book(
        title = "T",
        sourceType = SourceType.LOCAL_FILE,
        localFormat = BookFormat.EPUB,
        filePath = "/x/book.epub",
        fileFingerprint = fingerprint,
        addedAt = 0,
        updatedAt = 0
    )

    private fun record(
        fingerprint: String = sha,
        version: Int = EpubPrepareAccess.LOADER_VERSION,
        sectionMap: Map<Int, Long> = emptyMap()
    ) = EpubPrepareRecord(
        fingerprint = fingerprint,
        loaderVersion = version,
        sectionMap = sectionMap
    )

    // ── isPrepared ──────────────────────────────────────────

    @Test
    fun noRecordIsNotPrepared() {
        assertFalse(EpubPrepareAccess.isPrepared(null, book()))
    }

    @Test
    fun matchingRecordIsPrepared() {
        assertTrue(EpubPrepareAccess.isPrepared(record(), book()))
    }

    @Test
    fun staleLoaderVersionIsNotPrepared() {
        assertFalse(
            EpubPrepareAccess.isPrepared(record(version = 99), book())
        )
    }

    @Test
    fun changedFileIsNotPrepared() {
        assertFalse(
            EpubPrepareAccess.isPrepared(
                record(fingerprint = "b".repeat(64)), book()
            )
        )
    }

    @Test
    fun uncapturedBookFingerprintLetsTheRecordDecide() {
        assertTrue(EpubPrepareAccess.isPrepared(record(), book(null)))
    }

    @Test
    fun fingerprintFormMatchesReadingMemoryCodec() {
        // The record stores the bare sha; the book stores
        // "name|size|sha" — the comparison is against the sha
        // segment, so a rename alone never forces a re-prepare.
        val fp = ReadingMemoryCodec.fingerprintOf("renamed.epub", 10, sha)
        assertTrue(EpubPrepareAccess.isPrepared(record(), book(fp)))
    }

    // ── Contents door (Item 2e) ─────────────────────────────

    private val landed = listOf(
        Chapter(id = 501, bookId = 1, index = 0, title = "A", updatedAt = 0),
        Chapter(id = 502, bookId = 1, index = 1, title = "B", updatedAt = 0),
        Chapter(id = 503, bookId = 1, index = 2, title = "C", updatedAt = 0)
    )

    @Test
    fun contentsDoorTranslatesThroughTheSectionMap() {
        val rec = record(sectionMap = mapOf(0 to 501L, 2 to 503L))
        assertEquals(
            2,
            EpubPrepareAccess.contentsChapterIndex(
                rec, """{"section":2}""", landed, continueChapterIndex = 1
            )
        )
    }

    @Test
    fun contentsDoorMissLandsOnTheContinuePlace() {
        val rec = record(sectionMap = mapOf(0 to 501L))
        // Section 1 has no map entry (the parser skipped it).
        assertEquals(
            1,
            EpubPrepareAccess.contentsChapterIndex(
                rec, """{"section":1}""", landed, continueChapterIndex = 1
            )
        )
    }

    @Test
    fun contentsDoorUnparsableTargetLandsOnTheContinuePlace() {
        val rec = record(sectionMap = mapOf(0 to 501L))
        assertEquals(
            1,
            EpubPrepareAccess.contentsChapterIndex(
                rec, null, landed, continueChapterIndex = 1
            )
        )
        assertEquals(
            1,
            EpubPrepareAccess.contentsChapterIndex(
                rec, "not json", landed, continueChapterIndex = 1
            )
        )
    }

    @Test
    fun contentsDoorDanglingChapterIdLandsOnTheContinuePlace() {
        val rec = record(sectionMap = mapOf(0 to 999L))
        assertEquals(
            1,
            EpubPrepareAccess.contentsChapterIndex(
                rec, """{"section":0}""", landed, continueChapterIndex = 1
            )
        )
    }
}
