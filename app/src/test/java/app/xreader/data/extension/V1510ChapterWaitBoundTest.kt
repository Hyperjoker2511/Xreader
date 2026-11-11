package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.extension.player.V158FakeMihon
import app.xreader.data.files.BookStorage
import app.xreader.fixtures.HangChapterFetchSource
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.10 (B1) gate, on the I4 hang fixture: the page-side
 * ChapterWaitRegistry ages a chapters-pending wait to the
 * honest state at CHAPTER_PENDING_UI_BOUND_MS (injected
 * clock) while the shared call stays alive and is never
 * restarted — walk-count exactly 1 across open + Retry —
 * and releasing the fixture's deferred lands the chapters
 * on the open page (the registry clears at the completion
 * consumer's apply, landing point 2).
 *
 * The sync-level leg drives the REAL SourceBookSync player
 * road: a fake Mihon source whose getMangaUpdate parks on a
 * gate (V158FakeMihon's updateGate — the same latch shape
 * as the hang fixtures: a deferred nothing completes until
 * the test releases it).
 */
class V1510ChapterWaitBoundTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Registry + hang fixture (pure, injected clock) ────

    @Test
    fun `registry ages to the honest state at the bound while the call stays alive`() =
        runBlocking {
            val clock = AtomicLong(1_000_000L)
            val registry = ChapterWaitRegistry { clock.get() }
            val source = HangChapterFetchSource()

            registry.begin(7L)
            val walk = async { source.fetchChapters("book-7") }
            // Let the walk enter the gate.
            withTimeout(5_000) {
                while (!source.gate.wasStarted) {
                    kotlinx.coroutines.delay(10)
                }
            }
            assertFalse(registry.isStalled(7L))
            clock.addAndGet(SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS - 1)
            assertFalse(
                "one ms before the bound is still an honest wait",
                registry.isStalled(7L)
            )
            clock.addAndGet(1)
            assertTrue(
                "at the bound the state is the honest slow state",
                registry.isStalled(7L)
            )
            assertTrue("the shared call is still alive", source.gate.isAlive)
            assertFalse(walk.isCompleted)

            // Retry = re-join: begin() again keeps the original
            // start (the bound does not restart) and starts no
            // second walk.
            registry.begin(7L)
            assertEquals(
                1_000_000L, registry.startedAtMs(7L)
            )
            assertEquals(1, source.gate.walkCount)

            // Releasing the deferred lands the result on the
            // SAME call; landing clears the registry.
            source.gate.release(listOf("chapter-1", "chapter-2"))
            assertEquals(
                listOf("chapter-1", "chapter-2"), walk.await()
            )
            registry.markLanded(7L)
            assertNull(registry.entry(7L))
            assertFalse(registry.isStalled(7L))
            assertEquals(1, source.gate.walkCount)
        }

    @Test
    fun `bound constant has the pinned value`() {
        assertEquals(60_000L, SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS)
    }

    // ── Sync level: the real player road hangs, detaches,
    //    re-joins once, and lands through the consumer ────

    private inner class HangingStack {
        val clock = AtomicLong(5_000_000L)
        val filesDir = tmp.newFolder()
        val store = ExtensionStore(filesDir)
        val extDao = FakeInstalledExtensionDao()
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val source = V158FakeMihon()
        val pkg = "eu.kanade.tachiyomi.extension.en.hangpanels"
        val namespaced = "keiyoushi:$pkg:777"
        lateinit var service: ExtensionService
        lateinit var sync: SourceBookSync
        var extRowId: Long = -1
        lateinit var book: Book

        suspend fun build() {
            extRowId = extDao.insert(
                InstalledExtension(
                    repoId = 1,
                    packageId = pkg,
                    displayName = "HangPanels",
                    versionName = "1.0",
                    versionCode = 1,
                    kind = ExtensionKind.KEIYOUSHI,
                    sha256 = "abc",
                    apiLevel = 16,
                    languages = "all",
                    sourceIds = "777",
                    trustState = TrustState.TRUSTED,
                    installedAt = 0,
                    updatedAt = 0
                )
            )
            val transport = FakeTransport { FakeTransport.ok("") }
            service = ExtensionService(
                extDao, store,
                HostContextFactory(store, testSessionStore(), transport),
                FakeDexLoader(),
                keiyoushiLiveSources = {
                    mapOf(namespaced to source)
                }
            ) { FakeJsRuntime() }
            sync = SourceBookSync(
                bookDao = bookDao,
                chapterDao = chapterDao,
                sourceMapDao = FakeChapterSourceMapDao(),
                extensionDao = extDao,
                service = service,
                storage = BookStorage(filesDir),
                transport = transport,
                nowMs = { clock.get() },
                pageLoadTimeoutMs = 400
            )
            val bookId = bookDao.insert(
                Book(
                    title = "Alpha",
                    sourceType = SourceType.EXTENSION,
                    extensionId = extRowId,
                    remoteId = SourceBookIds.encode(
                        namespaced, "/manga/alpha"
                    ),
                    chapterListComplete = false,
                    addedAt = 0,
                    updatedAt = 0,
                    inLibrary = true
                )
            )
            book = bookDao.getById(bookId)!!
            source.updateGate = CompletableDeferred()
        }
    }

    @Test
    fun `hanging player refresh detaches at the page bound, re-joins once, lands via the consumer`() =
        runBlocking {
            val stack = HangingStack()
            stack.build()
            val sync = stack.sync
            val source = stack.source

            // Open: the refresh's wait is bounded (400 ms test
            // bound) — it returns details-state with chapters
            // still loading, the walk alive in the registry
            // call beneath it.
            val first = sync.refresh(stack.book)
            assertTrue(first is AppResult.Ok)
            assertEquals(
                "exactly one walk was started", 1, source.updateCalls.get()
            )
            assertTrue(
                "the wait is registered from the refresh start",
                sync.chapterWaits.entry(stack.book.id) != null
            )
            assertFalse(sync.chapterWaits.isStalled(stack.book.id))

            // Age the wait past the UI bound (injected clock):
            // the honest state, while the shared call is STILL
            // alive (its gate was never completed or cancelled).
            stack.clock.addAndGet(
                SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS
            )
            assertTrue(sync.chapterWaits.isStalled(stack.book.id))
            assertEquals(1, source.updateCalls.get())

            // Retry re-joins the in-flight call through the
            // single-flight registry — no second walk.
            val retry = sync.refresh(stack.book)
            assertTrue(retry is AppResult.Ok)
            assertEquals(
                "open + Retry start exactly one walk",
                1, source.updateCalls.get()
            )
            assertTrue(sync.chapterWaits.isStalled(stack.book.id))

            // Release the gate: the detached completion
            // consumer applies the chapters and the registry
            // clears (landing point 2).
            source.updateGate!!.complete(Unit)
            withTimeout(10_000) {
                while (stack.chapterDao.forBook(stack.book.id).isEmpty()) {
                    kotlinx.coroutines.delay(25)
                }
            }
            assertEquals(
                2, stack.chapterDao.forBook(stack.book.id).size
            )
            assertNull(
                "chapters landing clears the wait",
                sync.chapterWaits.entry(stack.book.id)
            )
            assertEquals(1, source.updateCalls.get())
        }
}
