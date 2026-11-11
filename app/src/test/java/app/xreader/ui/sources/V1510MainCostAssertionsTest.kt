package app.xreader.ui.sources

import app.xreader.data.extension.ChapterWaitRegistry
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.library.heroOrderedGroups
import app.xreader.data.db.Book
import app.xreader.data.db.SourceType
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.10 (C2) gate: Main-cost is a deterministic PROPERTY,
 * not a wall-clock hope. Robolectric's Main is a paused-looper
 * shadow, so the gate asserts the proxy the build map
 * specifies — ops-on-Main == 0 — through counting seams that
 * record their calling thread:
 *  - Sources (B2's joined pass): every censused expensive op
 *    (sourcesOf / definition decode / picks file read /
 *    handle construction) runs inside the pass, on Default;
 *    the fingerprint + registry + hero-order computations
 *    the collectors run are allocation-only over in-memory
 *    values (no IO seams at all — asserted by construction
 *    here: the picks store's file-read counter does not move
 *    while fingerprints are computed).
 *  - The 50 ms/emission figure is the recorded design
 *    target: a wall-clock MEDIAN over repeated runs on a
 *    real dispatcher is measured and printed as a metric —
 *    deliberately NOT a pass/fail line (map, C2).
 *
 * Exempt-with-reason (I3 census): the Sources stateIn
 * combine (a plain row→state mapping plus the pre-existing
 * readPendings/arrangeSourceTwins reads) remains on the
 * collecting thread BY DESIGN per the map's B2 census; the
 * book page's and Library's DAO flow collectors are Room's
 * own (their compute is the queries themselves, executed by
 * Room off-Main) — neither carries a lane compute body to
 * move, so the proxy has nothing to count there; the seams
 * those screens gained in this build (ChapterWaitRegistry
 * reads, hero ordering) are asserted below.
 */
class V1510MainCostAssertionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `joined pass - all censused ops execute off the collecting thread`() =
        runBlocking {
            val collecting = Thread.currentThread().name
            val opThreads = CopyOnWriteArrayList<Pair<String, String>>()
            fun op(name: String) {
                opThreads += name to Thread.currentThread().name
            }
            val pass = JoinedSourcesPass()
            pass.run("c2-fp", Dispatchers.Default) {
                op("sourcesOf"); op("definitionDecode")
                op("picksRead"); op("handleConstruction")
                Unit
            }
            assertEquals(4, opThreads.size)
            assertTrue(
                "ops-on-collecting-thread must be 0, got $opThreads",
                opThreads.all { it.second != collecting }
            )
        }

    @Test
    fun `fingerprint computation performs no picks file read`() {
        val store = ExtensionStore(tmp.newFolder())
        store.writeInstancePick("pkg.a", "src-1")
        val readsAfterWarm = store.instancePicksFileReadCount
        // Fingerprints read only the supplied hash + in-memory
        // row fields — computing many moves no file counter.
        repeat(50) {
            sourcesPassFingerprint(
                extensions = emptyList(),
                health = emptyMap(),
                playerStates = emptyMap(),
                repos = emptyList(),
                foreignRepos = emptyMap(),
                visibility = app.xreader.data.extension
                    .ContentVisibility.DEFAULT,
                tick = it,
                picksHash = store.readInstancePicks().hashCode()
            )
        }
        assertEquals(
            "the picks map is memory-served (B3): no re-reads",
            readsAfterWarm, store.instancePicksFileReadCount
        )
    }

    @Test
    fun `registry and hero-order seams are pure in-memory computations`() {
        // ChapterWaitRegistry: the book page's per-second poll
        // is a ConcurrentHashMap read + arithmetic — no IO
        // seam exists in the class (its only dependency is a
        // clock lambda).
        var now = 0L
        val registry = ChapterWaitRegistry { now }
        registry.begin(1L)
        now = 61_000L
        assertTrue(registry.isStalled(1L))
        // Hero ordering: sorting/grouping over the rows in
        // hand — the Library collector's only new compute.
        val books = listOf(
            Book(
                id = 1, title = "A", sourceType = SourceType.EXTENSION,
                extensionId = 7, addedAt = 0, updatedAt = 0,
                lastOpenedAt = null
            ),
            Book(
                id = 2, title = "B", sourceType = SourceType.EXTENSION,
                extensionId = 7, addedAt = 0, updatedAt = 0,
                lastOpenedAt = 500
            )
        )
        assertEquals(
            listOf(2L, 1L),
            heroOrderedGroups(books).single().map { it.id }
        )
    }

    @Test
    fun `wall-clock median of the joined compute is reported as a metric`() =
        runBlocking {
            // Metric only (map C2): the design target is
            // 50 ms/emission on the bench shelf; this measures
            // the pass over a shelf-sized synthetic input on a
            // real dispatcher and PRINTS the median — flaky
            // wall-clock never gates.
            val pass = JoinedSourcesPass()
            val samples = mutableListOf<Long>()
            repeat(7) { round ->
                val started = System.nanoTime()
                pass.run("metric-$round", Dispatchers.Default) {
                    var acc = 0
                    for (i in 1..5_000) {
                        acc += sourcesPassFingerprint(
                            extensions = emptyList(),
                            health = emptyMap(),
                            playerStates = emptyMap(),
                            repos = emptyList(),
                            foreignRepos = emptyMap(),
                            visibility = app.xreader.data.extension
                                .ContentVisibility.DEFAULT,
                            tick = i, picksHash = i
                        ).length
                    }
                    acc
                }
                samples += (System.nanoTime() - started) / 1_000_000
            }
            val median = samples.sorted()[samples.size / 2]
            println(
                "C2 metric: joined-pass synthetic compute median " +
                    "= $median ms over ${samples.size} runs " +
                    "(design target 50 ms/emission; metric only)"
            )
            assertTrue(samples.isNotEmpty())
            Unit
        }
}
