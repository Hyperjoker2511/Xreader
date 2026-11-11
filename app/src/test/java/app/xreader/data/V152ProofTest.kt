package app.xreader.data

import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.db.SourceType
import app.xreader.data.download.BodyOrigin152
import app.xreader.data.extension.BookKind152
import app.xreader.data.extension.SourceIdentity
import app.xreader.data.extension.SourceProvenance
import app.xreader.data.db.ExtensionKind
import app.xreader.data.extension.UpdatePass152
import app.xreader.data.library.FavouriteKeys
import app.xreader.data.sync.DeletedBookKey
import app.xreader.data.sync.SyncMerger
import app.xreader.data.sync.SyncPayload
import app.xreader.data.sync.BookRecord
import app.xreader.ui.reader.SlicePlan152
import app.xreader.data.prefs.PerfTier
import app.xreader.ui.sources.StreamingSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.2 machine proofs for the pure seams (§10 items 1/3-name,
 * 4-formula/footnotes, 6-precedence, 7-budgets, 8-kind, 9-storage,
 * 10-favourites, 12-sweep + tombstone). Journey/live legs are
 * reported separately in the build report — these tests pin the
 * mechanisms the journeys ride, with fixtures shaped from
 * pre-change persisted state (fossil "Home", rowid keys,
 * MANGA-stamped rows, unstamped bodies).
 */
class V152ProofTest {

    // ── §10 item 1/3 (name leg): fossil "Home" can never win ─────
    @Test
    fun `identity precedence - seed beats Home title`() {
        assertEquals(
            "BotiTranslation",
            SourceIdentity.resolve(
                seedName = "BotiTranslation",
                host = "www.botitranslation.com",
                ogSiteName = null,
                pageTitle = "Home"
            )
        )
    }

    @Test
    fun `identity precedence - host derived beats generic title`() {
        assertEquals(
            "Botitranslation",
            SourceIdentity.resolve(
                seedName = null,
                host = "botitranslation.com",
                ogSiteName = null,
                pageTitle = "Home"
            )
        )
        assertTrue(SourceIdentity.isGenericTitle("Home", "x.com", null))
        assertTrue(SourceIdentity.isGenericTitle("Welcome to Books", "x.com", null))
        assertFalse(SourceIdentity.isGenericTitle("FenrirRealm", "fenrirealm.com", null))
    }

    // ── §10 item 8: per-book kind ────────────────────────────────
    @Test
    fun `fenrir type table maps exactly the evidenced values`() {
        val map = BookKind152.FENRIR_TYPE_MAP
        assertEquals("TEXT", BookKind152.tier1(map, "novel"))
        assertEquals("TEXT", BookKind152.tier1(map, "web_novel"))
        assertEquals("TEXT", BookKind152.tier1(map, "light_novel"))
        assertEquals("TEXT", BookKind152.tier1(map, "original_novel"))
        assertEquals("MANGA", BookKind152.tier1(map, "webtoon"))
        // v1.5.8 (B5, per-book kind revision): the manga side was
        // DELIBERATELY widened beyond the single live-evidenced
        // `webtoon` — BookKind152.FENRIR_TYPE_MAP's own header
        // states the widened set as a best guess, backstopped by
        // chapter-shape evidence (B2/B3) and the unmapped-value
        // DiagnosticLog. These three now file MANGA by design.
        assertEquals("MANGA", BookKind152.tier1(map, "manhwa"))
        assertEquals("MANGA", BookKind152.tier1(map, "comic"))
        assertEquals("MANGA", BookKind152.tier1(map, "manga"))
        // Any other value → UNKNOWN, never guessed.
        assertNull(BookKind152.tier1(map, "manhua"))
        assertNull(BookKind152.tier1(map, ""))
        assertNull(BookKind152.tier1(map, null))
    }

    @Test
    fun `unknown kind files visually under novels and never vanishes`() {
        assertFalse(BookKind152.visualSideIsManga(null))
        assertFalse(BookKind152.visualSideIsManga("TEXT"))
        assertTrue(BookKind152.visualSideIsManga("MANGA"))
        assertEquals(BookKind152.Verdict.UNKNOWN, BookKind152.verdictOf(null))
    }

    @Test
    fun `tier2 hysteresis - single verdict never flips, second consistent does`() {
        // Chikari shape: single-kind source — tier 2 may not re-file at all.
        val (pinned, _) = BookKind152.applyTier2(
            storedKind = "TEXT", bothKindsSource = false,
            tier2Kind = "MANGA", pendingKind = null, pendingStreak = 0
        )
        assertEquals("TEXT", pinned)
        // Both-kinds: one MANGA verdict only pends; the second flips.
        val (after1, pend1) = BookKind152.applyTier2(
            storedKind = "TEXT", bothKindsSource = true,
            tier2Kind = "MANGA", pendingKind = null, pendingStreak = 0
        )
        assertEquals("TEXT", after1)
        val (after2, _) = BookKind152.applyTier2(
            storedKind = after1, bothKindsSource = true,
            tier2Kind = "MANGA", pendingKind = pend1.first, pendingStreak = pend1.second
        )
        assertEquals("MANGA", after2)
        // Tier 1 always overrides immediately.
        assertEquals("MANGA", BookKind152.applyTier1("TEXT", "MANGA"))
        assertEquals("TEXT", BookKind152.applyTier1("TEXT", null))
    }

    // ── §10 item 4: search envelope numbers + footnotes ─────────
    @Test
    fun `search aggregate formula is min of cap and waves times budget`() {
        assertEquals(20_000L, StreamingSearch.aggregateBoundMs(3))
        assertEquals(40_000L, StreamingSearch.aggregateBoundMs(6))
        assertEquals(120_000L, StreamingSearch.aggregateBoundMs(100))
        assertEquals(120_000L, StreamingSearch.AGGREGATE_DEADLINE_MS)
    }

    // ── §10 item 9: storage law ──────────────────────────────────
    private fun chapter(origin: String?, state: DownloadState, path: String? = "x") =
        Chapter(
            bookId = 1, index = 0, title = "c", contentPath = path,
            downloadState = state, updatedAt = 0, bodyOrigin = origin
        )

    @Test
    fun `downloaded means USER plus READY on every surface`() {
        assertTrue(BodyOrigin152.isDownloaded(chapter("USER", DownloadState.READY)))
        assertFalse(BodyOrigin152.isDownloaded(chapter("AUTOMATIC", DownloadState.READY)))
        assertFalse(BodyOrigin152.isDownloaded(chapter(null, DownloadState.READY)))
        assertFalse(BodyOrigin152.isDownloaded(chapter("USER", DownloadState.NONE)))
        assertTrue(BodyOrigin152.isCachedBody(chapter("AUTOMATIC", DownloadState.READY)))
        // §12.6: unstamped pre-fix READY body demotes (status only).
        assertEquals(
            "AUTOMATIC",
            BodyOrigin152.demotedOrigin(chapter(null, DownloadState.READY))
        )
    }

    @Test
    fun `eviction is LRU with pins and exemptions honoured`() {
        val mb = 1024L * 1024
        val entries = listOf(
            BodyOrigin152.BodyEntry(1, 10 * mb, lastReadAt = 1),
            BodyOrigin152.BodyEntry(2, 10 * mb, lastReadAt = 2),
            BodyOrigin152.BodyEntry(3, 10 * mb, lastReadAt = 3, pinned = true),
            BodyOrigin152.BodyEntry(4, 10 * mb, lastReadAt = 0, evictionExempt = true)
        )
        // v1.5.2 fix (FIX-6e, §12.7): the exempt body (4) stands
        // outside the managed cache — the managed total is 30MB
        // (1, 2 and the pinned 3), so a 25MB cap evicts only the
        // oldest unpinned body (1). The first build counted the
        // exempt bytes toward the cap, which made the cap
        // unreachable and evicted ordinary books wholesale.
        assertEquals(listOf(1L), BodyOrigin152.evictionPlan(entries, 25 * mb))
        assertEquals(emptyList<Long>(), BodyOrigin152.evictionPlan(entries, 100 * mb))
        // An exempt-heavy cache evicts nothing it may not touch:
        // 50MB exempt + 10MB managed under a 25MB cap is stable.
        val exemptHeavy = listOf(
            BodyOrigin152.BodyEntry(9, 50 * mb, lastReadAt = 0, evictionExempt = true),
            BodyOrigin152.BodyEntry(10, 10 * mb, lastReadAt = 5)
        )
        assertEquals(
            emptyList<Long>(),
            BodyOrigin152.evictionPlan(exemptHeavy, 25 * mb)
        )
        assertEquals(20L * 1024 * 1024, BodyOrigin152.TEXT_CAP_BYTES)
        assertEquals(100L * 1024 * 1024, BodyOrigin152.IMAGE_CAP_BYTES)
    }

    // ── §10 item 10: favourites key lifetime ────────────────────
    @Test
    fun `doubtful rowid keys drop, live rows convert`() {
        val migration = FavouriteKeys.migrate(
            oldRowIdKeys = setOf(7L, 9L, 12L),
            rowIdToNaturalKey = mapOf(7L to "ext:pkg:1", 9L to "local:a|b|3")
        )
        assertEquals(setOf("ext:pkg:1", "local:a|b|3"), migration.converted)
        assertEquals(1, migration.droppedCount)
    }

    @Test
    fun `merge transfers the favourite to the survivor by union`() {
        val merged = FavouriteKeys.mergedKeys(
            survivorKey = "k-survivor", duplicateKey = "k-dup",
            favourites = setOf("k-dup")
        )
        assertEquals(setOf("k-survivor"), merged)
    }

    // ── §10 item 12: sweep predicate + tombstone ────────────────
    @Test
    fun `sweep protects the F15-01 residue shape`() {
        // soft-removed + cache-freed + source-gone, WITH History record.
        assertFalse(
            UpdatePass152.sweepMayComplete(
                hasShelfMembership = false, inLibrary = false,
                hasLiveSource = false, hasLocalContent = false,
                hasHistoryRecord = true
            )
        )
        // Truly nothing left → may complete.
        assertTrue(
            UpdatePass152.sweepMayComplete(
                hasShelfMembership = false, inLibrary = false,
                hasLiveSource = false, hasLocalContent = false,
                hasHistoryRecord = false
            )
        )
        // Any single protection blocks completion.
        assertFalse(
            UpdatePass152.sweepMayComplete(
                hasShelfMembership = false, inLibrary = false,
                hasLiveSource = false, hasLocalContent = true,
                hasHistoryRecord = false
            )
        )
    }

    @Test
    fun `book tombstone is honoured by merge and cleared by newer re-add`() {
        val book = BookRecord(syncKey = "ext:p:1", title = "T", updatedAt = 100)
        val local = SyncPayload(books = listOf(book))
        val remote = SyncPayload(
            deletedBookKeys = listOf(DeletedBookKey("ext:p:1", deletedAt = 200))
        )
        val merged = SyncMerger.merge(local, remote, now = 300)
        assertTrue(merged.payload.books.isEmpty())
        assertEquals(1, merged.payload.deletedBookKeys.size)
        // Explicit re-add NEWER than the tombstone clears it.
        val readded = SyncPayload(
            books = listOf(book.copy(updatedAt = 500))
        )
        val merged2 = SyncMerger.merge(readded, remote, now = 600)
        assertEquals(1, merged2.payload.books.size)
        assertTrue(merged2.payload.deletedBookKeys.isEmpty())
    }

    // ── §10 item 7: per-tier decode budgets (§6.2 printed numbers) ──
    @Test
    fun `slice budgets match the printed per-tier numbers`() {
        assertEquals(2048, SlicePlan152.decodeWidthPx(PerfTier.FULL, 1080))
        assertEquals(2048, SlicePlan152.decodeWidthPx(PerfTier.STANDARD, 1080))
        assertEquals(1080, SlicePlan152.decodeWidthPx(PerfTier.MINIMAL, 1080))
        assertEquals(3072, SlicePlan152.sliceHeightPx(PerfTier.FULL))
        assertEquals(1536, SlicePlan152.sliceHeightPx(PerfTier.MINIMAL))
        assertEquals(32L * 1024 * 1024, SlicePlan152.residentCeilingBytes(PerfTier.MINIMAL))
        assertEquals(64L * 1024 * 1024, SlicePlan152.residentCeilingBytes(PerfTier.STANDARD))
        assertEquals(96L * 1024 * 1024, SlicePlan152.residentCeilingBytes(PerfTier.FULL))
        assertEquals(1, SlicePlan152.decodeConcurrency(PerfTier.MINIMAL))
        assertEquals(2, SlicePlan152.decodeConcurrency(PerfTier.FULL))
        // MINIMAL residency: 1600-wide slices at 1536 high =
        // ~9.8MB each → ceiling binds at 3, count limit is also 3.
        assertTrue(SlicePlan152.residentSlicesFor(PerfTier.MINIMAL, 1600) <= 3)
        val bytes = SlicePlan152.residentSlicesFor(PerfTier.MINIMAL, 1600) *
            SlicePlan152.sliceBytes(1600, 1536)
        assertTrue(bytes <= 32L * 1024 * 1024)
        // Zoom re-decode: 2x target bounded by source-native width.
        assertEquals(1600, SlicePlan152.zoomTargetWidthPx(800, 4000))
        assertEquals(1200, SlicePlan152.zoomTargetWidthPx(800, 1200))
    }

    // ── §8.6 provenance labels ───────────────────────────────────
    @Test
    fun `provenance derives at read time`() {
        assertEquals(
            "Added by you via link",
            SourceProvenance.label("My link sources", true, false, "live.x.com", ExtensionKind.LIVE)
        )
        assertEquals(
            "Shipped with Xreader",
            SourceProvenance.label("Built-in sources", false, true, "bundled.x", ExtensionKind.DECLARATIVE)
        )
        assertEquals(
            "From a provider pack",
            SourceProvenance.label("Pack repo", false, false, "packs.foo", ExtensionKind.DECLARATIVE)
        )
        assertEquals(
            "Keiyoushi extension",
            SourceProvenance.label("Keiyoushi", false, false, "eu.kanade.x", ExtensionKind.KEIYOUSHI)
        )
    }

    // ── §14.4: the counter itself (investigation fixture) ───────
    @Test
    fun `wordCount sums heading and paragraph blocks only`() {
        val content = app.xreader.domain.content.ChapterContent(
            blocks = listOf(
                app.xreader.domain.content.ChapterBlock.Heading(1, "One two"),
                app.xreader.domain.content.ChapterBlock.Paragraph.plain("three four five"),
                app.xreader.domain.content.ChapterBlock.Image("y.png")
            )
        )
        // Independent plain-text split over the same prose = 5.
        assertEquals(5, content.wordCount)
    }
}
