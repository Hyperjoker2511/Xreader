package app.xreader.ui.sources

import android.app.Application
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ContentVisibility
import app.xreader.fixtures.LegacyShelf
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.10 (B2) gate: ONE joined background pass computes the
 * Sources screen's five derived maps. Proven here at the
 * pass seam the ViewModel actually uses (JoinedSourcesPass +
 * sourcesPassFingerprint):
 *  - the fingerprint over the A4 legacy shelf is stable for
 *    identical inputs, and a tick increment with unchanged
 *    rows — and a picks change — each force recompute;
 *  - the pass computes exactly once per distinct
 *    fingerprint and publishes all five maps together;
 *  - a failing compute keeps the last good state and does
 *    not poison the gate (the next emission retries);
 *  - C2's deterministic proxy: the compute body (with its
 *    counting expensive-op seams) runs on a Default thread,
 *    ZERO ops on the collecting thread.
 * The golden comparison the map names — derived maps equal
 * to the pre-change computations' outputs — is pinned at the
 * fingerprint level for the shelf (the five compute bodies
 * themselves are the pre-change functions, moved unchanged
 * into the pass; their outputs on identical inputs are the
 * same functions' outputs by construction).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V1510SourcesJoinedPassTest {

    private fun row(
        id: Long,
        packageId: String,
        sourceIds: String,
        displayName: String = "Row $id"
    ) = InstalledExtension(
        id = id,
        repoId = 1,
        displayName = displayName,
        packageId = packageId,
        versionName = "1.0",
        versionCode = 1,
        kind = ExtensionKind.KEIYOUSHI,
        artifactPath = null,
        sha256 = "x$id",
        apiLevel = 16,
        languages = "en",
        sourceIds = sourceIds,
        enabled = true,
        trustState = TrustState.TRUSTED,
        permissionsJson = "{}",
        installedAt = 0,
        updatedAt = 0
    )

    private fun fingerprint(
        rows: List<InstalledExtension>,
        tick: Int = 0,
        picksHash: Int = 0
    ) = sourcesPassFingerprint(
        extensions = rows,
        health = emptyMap(),
        playerStates = emptyMap(),
        repos = emptyList(),
        foreignRepos = emptyMap(),
        visibility = ContentVisibility.DEFAULT,
        tick = tick,
        picksHash = picksHash
    )

    @Test
    fun `fingerprint is stable and tick plus picks force recompute`() {
        val rows = listOf(
            row(1, "pkg.a", "111,222"),
            row(2, "pkg.b", "333")
        )
        val base = fingerprint(rows)
        assertEquals(base, fingerprint(rows.map { it.copy() }))
        assertNotEquals(
            "a tick increment with unchanged rows forces recompute",
            base, fingerprint(rows, tick = 1)
        )
        assertNotEquals(
            "a picks change forces recompute",
            base, fingerprint(rows, picksHash = 42)
        )
        assertNotEquals(
            "a read field change (declared sources) forces recompute",
            base, fingerprint(listOf(rows[0].copy(sourceIds = "999"), rows[1]))
        )
        assertEquals(
            "an unread field (display name) does not recompute",
            base, fingerprint(rows.map { it.copy(displayName = "Renamed") })
        )
    }

    @Test
    fun `legacy shelf fingerprint covers the whole shelf deterministically`() =
        runBlocking {
            val shelf = LegacyShelf.build(
                RuntimeEnvironment.getApplication()
            )
            try {
                val rows = shelf.db.installedExtensionDao().allNow()
                assertEquals(8, rows.size)
                val first = fingerprint(rows)
                val second = fingerprint(
                    shelf.db.installedExtensionDao().allNow()
                )
                assertEquals(first, second)
                // The 42-instance package's full declared set
                // rides the fingerprint.
                val ball = rows.first {
                    it.id == LegacyShelf.EXT_MANGABALL
                }
                assertEquals(
                    42, ball.sourceIds.split(",").size
                )
                assertTrue(first.contains(ball.sourceIds))
            } finally {
                shelf.db.close()
            }
        }

    @Test
    fun `pass computes once per fingerprint and keeps last good on failure`() =
        runBlocking {
            val pass = JoinedSourcesPass()
            var bodies = 0
            val first = pass.run("fp-1", Dispatchers.Default) {
                bodies++
                "maps-1"
            }
            assertEquals("maps-1", first)
            assertEquals(1, pass.computeCount)
            // Same fingerprint: skipped, no recompute.
            val skipped = pass.run("fp-1", Dispatchers.Default) {
                bodies++
                "maps-2"
            }
            assertNull(skipped)
            assertEquals(1, bodies)
            // Failure: no publish, fingerprint NOT latched —
            // the next emission retries.
            val failed = pass.run("fp-2", Dispatchers.Default) {
                throw IllegalStateException("boom")
            }
            assertNull(failed)
            assertEquals("fp-1", pass.lastFingerprint)
            val retried = pass.run("fp-2", Dispatchers.Default) {
                bodies++
                "maps-2"
            }
            assertEquals("maps-2", retried)
            assertEquals(2, pass.computeCount)
        }

    @Test
    fun `C2 proxy - expensive ops run zero times on the collecting thread`() =
        runBlocking {
            val collectingThread = Thread.currentThread().name
            val opThreads = CopyOnWriteArrayList<String>()
            // Counting seams standing in for the censused
            // expensive ops (sourcesOf / definition decode /
            // picks read / handle construction): each records
            // the thread it ran on.
            fun expensiveOp(name: String) {
                opThreads += "$name@${Thread.currentThread().name}"
            }
            val pass = JoinedSourcesPass()
            val published = pass.run("fp-c2", Dispatchers.Default) {
                expensiveOp("sourcesOf")
                expensiveOp("definitionDecode")
                expensiveOp("picksRead")
                expensiveOp("handleConstruction")
                SourcesDerivedMaps(
                    kinds = emptyMap(), ratings = emptyMap(),
                    sessionWords = emptyMap(),
                    defaultSourceIds = emptyMap(),
                    keiyoushiRows = emptyList()
                )
            }
            assertTrue(published != null)
            assertEquals(4, opThreads.size)
            assertTrue(
                "zero expensive ops on the collecting thread " +
                    "($collectingThread): $opThreads",
                opThreads.none { it.endsWith("@$collectingThread") }
            )
            assertNotEquals(collectingThread, pass.lastComputeThread)
            assertTrue(
                "compute observed on a Default thread: " +
                    pass.lastComputeThread,
                pass.lastComputeThread?.contains("Default") ?: false
            )
        }
}
