package app.xreader.data.extension

import app.xreader.fixtures.HangPageListSource
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.5.10 (B4) gate: both reader roads (Komikku page list,
 * novel chapter text) reach the honest stall state at the
 * shared bound while their hang-fixture calls stay alive;
 * every bound-crossing stall writes its DiagnosticLog
 * record; and leaving (cancelling the wait — the Back
 * road) exits cleanly with no record faked afterwards.
 *
 * The ViewModels arm exactly this seam (ReaderStall over
 * CHAPTER_PENDING_UI_BOUND_MS, imported from SourceBookSync)
 * around their loads; the seam + fixture are driven here at
 * the level the JVM can prove.
 */
class V1510ReaderStallStateTest {

    @Before
    fun setUp() {
        DiagnosticLog.clear()
    }

    @Test
    fun `stall verdict flips exactly at the bound`() {
        val bound = SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS
        assertFalse(ReaderStall.isStalled(1_000L, 1_000L + bound - 1))
        assertTrue(ReaderStall.isStalled(1_000L, 1_000L + bound))
        assertTrue(ReaderStall.isStalled(1_000L, 1_000L + bound + 5_000))
    }

    @Test
    fun `komikku road - stall state at the bound, record written, Back exits cleanly`() =
        runBlocking {
            val source = HangPageListSource()
            val startedAt = 10_000L
            val bound = SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS

            val walk = async { source.fetchPages("chapter-1") }
            kotlinx.coroutines.withTimeout(5_000) {
                while (!source.komikkuGate.wasStarted) {
                    kotlinx.coroutines.delay(10)
                }
            }
            // Before the bound: no stall, no record.
            assertFalse(
                ReaderStall.recordIfStalled(
                    sourceId = "777", awaited = "chapter 1 pages",
                    startedAtMs = startedAt,
                    nowMs = startedAt + bound - 1
                )
            )
            assertTrue(DiagnosticLog.entries().isEmpty())
            // At the bound: stall + record, call still alive.
            assertTrue(
                ReaderStall.recordIfStalled(
                    sourceId = "777", awaited = "chapter 1 pages",
                    startedAtMs = startedAt,
                    nowMs = startedAt + bound
                )
            )
            val record = DiagnosticLog.entries().single()
            assertEquals("stall", record.tag)
            assertTrue(record.message.contains("777"))
            assertTrue(record.message.contains("chapter 1 pages"))
            assertTrue(record.detail.contains("stillRunning=true"))
            assertTrue(source.komikkuGate.isAlive)
            assertEquals(1, source.komikkuGate.walkCount)

            // Back: cancelling the wait exits cleanly — the
            // parked call unwinds, nothing else is recorded.
            walk.cancelAndJoin()
            assertTrue(walk.isCancelled)
            assertFalse(source.komikkuGate.isAlive)
            assertEquals(1, DiagnosticLog.entries().size)
        }

    @Test
    fun `novel road - stall state at the bound with its own record`() =
        runBlocking {
            val source = HangPageListSource()
            val startedAt = 50_000L
            val bound = SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS

            val walk = async { source.fetchChapterText("chapter-9") }
            kotlinx.coroutines.withTimeout(5_000) {
                while (!source.novelGate.wasStarted) {
                    kotlinx.coroutines.delay(10)
                }
            }
            assertTrue(
                ReaderStall.recordIfStalled(
                    sourceId = "novels-example",
                    awaited = "chapter 9 text",
                    startedAtMs = startedAt,
                    nowMs = startedAt + bound + 1
                )
            )
            val record = DiagnosticLog.entries().single()
            assertEquals("stall", record.tag)
            assertTrue(record.message.contains("novels-example"))
            assertTrue(record.message.contains("chapter 9 text"))
            assertTrue(source.novelGate.isAlive)

            walk.cancelAndJoin()
            assertTrue(walk.isCancelled)
            assertEquals(
                "the stall message is the map's exact copy",
                "Still loading — this chapter is taking long",
                ReaderStall.STALL_MESSAGE
            )
        }
}
