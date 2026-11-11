package app.xreader.ui.book

import app.xreader.data.db.Chapter
import app.xreader.data.extension.ChapterReconciler
import app.xreader.extensionapi.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.7 (Finding 5, PRIMARY — owner evidence, all sources):
 * the complete-chapter-list invariant at the SHARED seam.
 *
 * The shared machinery every novel source's list flows
 * through is SourceBookSync + ChapterReconciler (fetch →
 * reconcile plan → one-transaction apply): bundled
 * declarative sources grow through its append-only partial
 * reconcile, player/lane sources land through its full
 * reconcile on refresh, and the huge-book walk returns
 * cumulative reading-order prefixes to the same merge. These
 * tests exercise THAT seam with synthetic fixtures in each
 * family's entry shape, plus the book page's head-heal
 * decision (ChapterListIntegrity) for a store that already
 * lost its beginning.
 *
 * [applyPlanLikeSync] mirrors SourceBookSync.applyPlan's
 * documented semantics (dropped removed; updates + kept-stale
 * rewritten at their new indices; inserts at their fetched
 * indices; rows a partial plan never mentions left exactly
 * as they are) so a plan can be asserted as the stored list
 * it produces.
 */
class ChapterListIntegrityTest {

    private fun stored(
        id: Long,
        index: Int,
        number: Int,
        idPrefix: String = "https://s.example/book/chapter-"
    ) = Chapter(
        bookId = 1L, id = id, index = index,
        title = "Chapter $number",
        remoteId = "$idPrefix$number",
        updatedAt = 0L
    )

    private fun fetched(number: Int, idPrefix: String): SourceChapter =
        SourceChapter(
            id = "$idPrefix$number",
            name = "Chapter $number",
            number = number.toFloat()
        )

    private fun applyPlanLikeSync(
        existing: List<Chapter>,
        plan: ChapterReconciler.Plan
    ): List<Chapter> {
        val dropped = plan.dropped.map { it.id }.toSet()
        val rows = existing.filter { it.id !in dropped }
            .associateBy { it.id }
            .toMutableMap()
        (plan.updates + plan.keptStale).forEach { update ->
            rows[update.row.id] = update.row.copy(
                index = update.newIndex,
                title = update.newTitle,
                remoteId = update.newRemoteId,
                dateText = update.newDateText
            )
        }
        var nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1L
        plan.inserts.forEach { insert ->
            val row = Chapter(
                bookId = 1L, id = nextId++, index = insert.index,
                title = insert.fetched.name,
                remoteId = insert.fetched.id,
                updatedAt = 0L,
                dateText = insert.fetched.dateText
            )
            rows[row.id] = row
        }
        return rows.values.sortedBy { it.index }
    }

    private fun assertCompleteOrdered(
        rows: List<Chapter>,
        expectedNumbers: IntRange,
        context: String
    ) {
        assertEquals(
            "$context: size",
            expectedNumbers.count(), rows.size
        )
        assertEquals(
            "$context: numbers in reading order",
            expectedNumbers.toList(),
            rows.map { ChapterJump.identityNumber(it)?.toInt() }
        )
        assertEquals(
            "$context: chapter 1 at the head",
            1, ChapterJump.identityNumber(rows.first())?.toInt()
        )
        assertTrue(
            "$context: contiguous indices",
            ChapterListIntegrity.hasContiguousIndices(rows)
        )
        assertFalse(
            "$context: head not missing",
            ChapterListIntegrity.headMissing(rows)
        )
    }

    // ── (a) bundled declarative shape: append-only batches ──

    @Test
    fun `declarative batches append - head survives every batch`() {
        // A bundled declarative book grows by cumulative
        // reading-order prefixes (the walk's shape): each
        // batch goes through the append-only PARTIAL
        // reconcile the sync uses for progressive chunks.
        val prefix = "https://bundled.example/book/chapter-"
        var storedRows = emptyList<Chapter>()
        var grown = 0
        for (batchEnd in listOf(100, 250, 500, 800, 1000, 1200)) {
            val cumulative = (1..batchEnd).map { fetched(it, prefix) }
            val plan = ChapterReconciler.reconcilePartial(
                storedRows, cumulative
            )
            // A partial plan must never drop or park a row.
            assertTrue("batch to $batchEnd drops nothing",
                plan.dropped.isEmpty())
            assertTrue("batch to $batchEnd keeps no stale",
                plan.keptStale.isEmpty())
            storedRows = applyPlanLikeSync(storedRows, plan)
            grown = batchEnd
            assertCompleteOrdered(storedRows, 1..grown, "batch to $batchEnd")
        }
    }

    @Test
    fun `declarative reversed walk prefixes keep chapter 1 first`() {
        // Novelight shape (reversed source): the walk hands
        // back READING-order prefixes that start at chapter 1
        // and extend at the tail — batch 1 is chapters 1..50,
        // never the newest window.
        val prefix = "https://novelight.example/book/x/chapter/"
        var storedRows = emptyList<Chapter>()
        for (batchEnd in listOf(50, 150, 400, 1200)) {
            val cumulative = (1..batchEnd).map { n ->
                SourceChapter(
                    id = "$prefix${900_000 + n}",
                    name = "$n chapter",
                    number = n.toFloat()
                )
            }
            val plan = ChapterReconciler.reconcilePartial(
                storedRows, cumulative
            )
            storedRows = applyPlanLikeSync(storedRows, plan)
            assertEquals(
                "batch to $batchEnd: head",
                1, ChapterJump.identityNumber(storedRows.first())?.toInt()
            )
            assertEquals(batchEnd, storedRows.size)
        }
        assertCompleteOrdered(storedRows, 1..1200, "reversed walk final")
    }

    // ── (b) player / lane shape: full reconcile on refresh ──

    @Test
    fun `player refresh with new chapters appends and keeps the head`() {
        // A Keiyoushi/player (lane) book: no progressive walk —
        // every refresh reconciles the donor's FULL list
        // through the same shared seam.
        val prefix = "https://lane.example/series/chapter-"
        val first = (1..300).map { fetched(it, prefix) }
        var storedRows = applyPlanLikeSync(
            emptyList(), ChapterReconciler.reconcile(emptyList(), first)
        )
        assertCompleteOrdered(storedRows, 1..300, "player first refresh")

        // The source releases 20 more chapters; the refresh's
        // full list is the old set plus the new tail.
        val second = (1..320).map { fetched(it, prefix) }
        val plan = ChapterReconciler.reconcile(storedRows, second)
        assertTrue("no stored row is dropped by new-chapter refresh",
            plan.dropped.isEmpty())
        storedRows = applyPlanLikeSync(storedRows, plan)
        assertCompleteOrdered(storedRows, 1..320, "player second refresh")
    }

    @Test
    fun `full reconcile of the identical list is a no-op for the head`() {
        val prefix = "https://lane.example/series/chapter-"
        val full = (1..120).map { fetched(it, prefix) }
        var storedRows = applyPlanLikeSync(
            emptyList(), ChapterReconciler.reconcile(emptyList(), full)
        )
        val again = ChapterReconciler.reconcile(storedRows, full)
        assertTrue(again.inserts.isEmpty())
        assertTrue(again.dropped.isEmpty())
        storedRows = applyPlanLikeSync(storedRows, again)
        assertCompleteOrdered(storedRows, 1..120, "idempotent refresh")
    }

    // ── (c) the huge book: head-loss detection + heal ───────

    /** The owner's stored shape after the loss: chapters
     * 464..1200, indices re-based to 0..736 by the store. */
    private fun headlessStored(): List<Chapter> =
        (464..1200).mapIndexed { i, n ->
            stored(id = n.toLong(), index = i, number = n)
        }

    @Test
    fun `head loss is detected on the stored shape`() {
        assertTrue(ChapterListIntegrity.headMissing(headlessStored()))
        assertEquals(
            464f,
            ChapterListIntegrity.earliestNumber(headlessStored())
        )
        // Healthy shapes never trip the detector.
        val healthy = (1..1200).mapIndexed { i, n ->
            stored(id = n.toLong(), index = i, number = n)
        }
        assertFalse(ChapterListIntegrity.headMissing(healthy))
        // A prologue book (chapter 0 first) is healthy.
        val prologue = listOf(
            stored(id = 1L, index = 0, number = 1)
                .copy(title = "Chapter 0"),
            stored(id = 2L, index = 1, number = 1),
            stored(id = 3L, index = 2, number = 2),
            stored(id = 4L, index = 3, number = 3)
        )
        assertFalse(ChapterListIntegrity.headMissing(prologue))
        // Too few numbered rows to judge: never condemned.
        val tiny = listOf(
            stored(id = 1L, index = 0, number = 9),
            stored(id = 2L, index = 1, number = 10)
        )
        assertFalse(ChapterListIntegrity.headMissing(tiny))
    }

    @Test
    fun `pre-broken stored list heals on refresh to the full ordered set`() {
        val prefix = "https://s.example/book/chapter-"
        val broken = headlessStored()
        // The heal fetch returns the source's FULL list; the
        // decision restores it, and the shared seam's full
        // reconcile lands it as the complete ordered set.
        val fetchedRows = (1..1200).mapIndexed { i, n ->
            stored(id = 5_000L + n, index = i, number = n)
        }
        val decision = ChapterListIntegrity.healDecision(broken, fetchedRows)
        assertTrue(decision is ChapterListIntegrity.HealDecision.Restore)

        val fetchedSource = (1..1200).map { fetched(it, prefix) }
        val plan = ChapterReconciler.reconcile(broken, fetchedSource)
        val healed = applyPlanLikeSync(broken, plan)
        assertCompleteOrdered(healed, 1..1200, "healed list")
        // The chapters the user already had keep their rows
        // (identity match), instead of being re-inserted.
        assertEquals(
            "chapter 1200 keeps its stored row id",
            1200L,
            healed.last().id
        )
    }

    @Test
    fun `a failed or empty heal fetch keeps the stored list`() {
        val broken = headlessStored()
        assertTrue(
            ChapterListIntegrity.healDecision(broken, null)
                is ChapterListIntegrity.HealDecision.KeepStored
        )
        assertTrue(
            ChapterListIntegrity.healDecision(broken, emptyList())
                is ChapterListIntegrity.HealDecision.KeepStored
        )
        // A fetched list that starts no earlier is no heal.
        val stillHeadless = (500..1200).mapIndexed { i, n ->
            stored(id = 9_000L + n, index = i, number = n)
        }
        assertTrue(
            ChapterListIntegrity.healDecision(broken, stillHeadless)
                is ChapterListIntegrity.HealDecision.KeepStored
        )
        // A healthy stored list needs no heal at all.
        val healthy = (1..10).mapIndexed { i, n ->
            stored(id = n.toLong(), index = i, number = n)
        }
        assertTrue(
            ChapterListIntegrity.healDecision(healthy, null)
                is ChapterListIntegrity.HealDecision.NotNeeded
        )
    }

    @Test
    fun `manga books share the same stored-list seam`() {
        // Manga chapter lists are stored as the same Chapter
        // rows in the same table, written by the same
        // SourceBookSync seam (the Komikku reader reads them
        // back via chapterDao.forBook) — so the invariant and
        // the head detector apply to them unchanged. A manga
        // shaped list (donor numbers, slug ids) behaves the
        // same under both.
        val prefix = "https://manga.example/title/chapter-"
        var storedRows = emptyList<Chapter>()
        val full = (1..64).map { fetched(it, prefix) }
        storedRows = applyPlanLikeSync(
            storedRows, ChapterReconciler.reconcile(storedRows, full)
        )
        assertCompleteOrdered(storedRows, 1..64, "manga list")
        val headlessManga = (9..64).mapIndexed { i, n ->
            stored(id = n.toLong(), index = i, number = n, idPrefix = prefix)
        }
        assertTrue(ChapterListIntegrity.headMissing(headlessManga))
    }
}
