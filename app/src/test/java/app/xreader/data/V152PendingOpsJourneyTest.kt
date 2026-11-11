package app.xreader.data

import android.app.Application
import androidx.room.Room
import app.xreader.core.error.AppResult
import app.xreader.data.db.PendingOperationStore
import app.xreader.data.db.XreaderDatabase
import app.xreader.ui.sources.loginCookiesByOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.2 (FIX-2, §1 / §10 item 2): the pending-operation
 * journey in its navigation shapes, against the REAL store on
 * a REAL Room database — (a) exactly-one claim, (b) completion
 * with zero collectors (promotion is evaluation-driven, never
 * event-driven), (c) death-during-solve recovery, (d) exactly-one
 * PROMOTION under concurrent evaluation (compare-and-swap; the
 * persisted trace shows it once) plus the rapid-switch family
 * (re-register after a terminal state, idempotent re-evaluation,
 * the full lifecycle trace), (e) NO DUPLICATE INSTALL when the
 * claimed re-probe's save runs twice (the §1 effect idempotency),
 * and capture completeness (the capture candidate set includes
 * the source's configured hosts).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V152PendingOpsJourneyTest {

    private fun store(): Pair<XreaderDatabase, PendingOperationStore> {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, XreaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return db to PendingOperationStore(db.pendingOperationDao())
    }

    private val key = "ext-42"
    private val w0 = PendingOperationStore.watermarkOf("cookies-v0")
    private val w1 = PendingOperationStore.watermarkOf("cookies-v1")

    @Test
    fun `exactly one claim wins among concurrent claimers`() = runBlocking {
        val (db, store) = store()
        store.register(key, "SOURCE_LOGIN", "site.example", w0, now = 1_000L)
        val promoted = store.evaluate(key, w1, now = 2_000L)
        assertEquals("READY", promoted!!.state)
        val wins = (1..16).map {
            async(Dispatchers.IO) { store.claim(key, "tok-$it") }
        }.awaitAll().count { it }
        assertEquals(1, wins)
        assertEquals(
            "RUNNING", db.pendingOperationDao().getByKey(key)!!.state
        )
        // The winner finishing lands the operation in a terminal
        // state — never RUNNING-forever.
        store.finish(key, success = true)
        assertEquals(
            "SUCCEEDED", db.pendingOperationDao().getByKey(key)!!.state
        )
        db.close()
    }

    @Test
    fun `zero-collector completion - promotion needs no event and no UI`() =
        runBlocking {
            val (db, store) = store()
            store.register(key, "LINK_ADD", "site.example", w0, now = 1_000L)
            // Nobody is collecting anything; the session simply
            // changed in the vault. The next evaluation — from
            // any surface resume or foreground — promotes it.
            val evaluated = store.evaluate(key, w1, now = 5_000L)
            assertEquals("READY", evaluated!!.state)
            assertTrue(store.claim(key, "surface-claim"))
            store.finish(key, success = true)
            assertEquals(
                "SUCCEEDED", db.pendingOperationDao().getByKey(key)!!.state
            )
            db.close()
        }

    @Test
    fun `death during solve - RUNNING at process start recovers to READY`() =
        runBlocking {
            val (db, store) = store()
            store.register(key, "SOURCE_LOGIN", "site.example", w0, now = 1_000L)
            store.evaluate(key, w1, now = 2_000L)
            assertTrue(store.claim(key, "doomed-claim"))
            assertEquals(
                "RUNNING", db.pendingOperationDao().getByKey(key)!!.state
            )
            // The process died mid-solve. At the next process
            // start the record must not sit RUNNING forever:
            // evaluation recovers it and a new claim can win.
            val recovered = store.evaluate(
                key, w1, now = 3_000L, processStart = true
            )
            assertEquals("READY", recovered!!.state)
            assertTrue(store.claim(key, "recovery-claim"))
            store.finish(key, success = true)
            assertEquals(
                "SUCCEEDED", db.pendingOperationDao().getByKey(key)!!.state
            )
            db.close()
        }

    @Test
    fun `re-register after a terminal state works - no swallowed collision`() =
        runBlocking {
            val (db, store) = store()
            store.register(key, "SOURCE_LOGIN", "site.example", w0, now = 1_000L)
            store.evaluate(key, w1, now = 2_000L)
            assertTrue(store.claim(key, "first"))
            store.finish(key, success = false, failureClass = "network")
            assertEquals(
                "FAILED", db.pendingOperationDao().getByKey(key)!!.state
            )
            // A legitimate second solve for the same source:
            // registration must produce a fresh WAITING record.
            val again = store.register(
                key, "SOURCE_LOGIN", "site.example", w1, now = 10_000L
            )
            assertEquals("WAITING", again.state)
            val promoted = store.evaluate(
                key, PendingOperationStore.watermarkOf("cookies-v2"),
                now = 11_000L
            )
            assertEquals("READY", promoted!!.state)
            assertTrue(store.claim(key, "second"))
            db.close()
        }

    @Test
    fun `cancel is terminal and prune clears old terminal records`() =
        runBlocking {
            val (db, store) = store()
            store.register(key, "LINK_ADD", "site.example", w0, now = 1_000L)
            store.cancel(key)
            assertEquals(
                "CANCELLED", db.pendingOperationDao().getByKey(key)!!.state
            )
            val removed = store.prune(
                now = 1_000L + 8L * 24 * 3600 * 1000
            )
            assertEquals(1, removed)
            assertNull(db.pendingOperationDao().getByKey(key))
            db.close()
        }

    @Test
    fun `exactly one promotion - concurrent evaluators promote once and trace once`() =
        runBlocking {
            val (db, store) = store()
            store.register(
                key, "SOURCE_LOGIN", "site.example", w0,
                category = "SIGN_IN", now = 1_000L
            )
            // Every waiting surface evaluates at once (resume,
            // foreground, the event fast path): promotion is a
            // compare-and-swap, so WAITING → READY lands exactly
            // once however many evaluators race.
            val outcomes = (1..16).map {
                async(Dispatchers.IO) { store.evaluate(key, w1, now = 2_000L) }
            }.awaitAll()
            assertTrue(outcomes.all { it?.state == "READY" })
            val record = db.pendingOperationDao().getByKey(key)!!
            assertEquals("READY", record.state)
            val promotions = record.trace.split(";")
                .count { it.startsWith("WAITING>READY@") }
            assertEquals(
                "trace must show exactly one promotion: ${record.trace}",
                1, promotions
            )
            // A later evaluation changes nothing and traces nothing.
            store.evaluate(key, w1, now = 3_000L)
            val after = db.pendingOperationDao().getByKey(key)!!
            assertEquals(record.trace, after.trace)
            db.close()
        }

    @Test
    fun `the full lifecycle is persisted in the record trace`() = runBlocking {
        val (db, store) = store()
        store.register(
            key, "LINK_ADD", "site.example", w0,
            category = "CHALLENGE", now = 1_000L
        )
        store.evaluate(key, w1, now = 2_000L)
        assertTrue(store.claim(key, "tok", now = 3_000L))
        store.finish(key, success = true, now = 4_000L)
        val record = db.pendingOperationDao().getByKey(key)!!
        assertEquals("SUCCEEDED", record.state)
        assertEquals(
            listOf(
                "NONE>WAITING@1000:CHALLENGE",
                "WAITING>READY@2000:CHALLENGE",
                "READY>RUNNING@3000:CHALLENGE",
                "RUNNING>SUCCEEDED@4000:CHALLENGE"
            ),
            record.trace.split(";")
        )
        // A terminal record never transitions again: a stray
        // finish/cancel leaves state and trace untouched.
        store.finish(key, success = false, now = 5_000L)
        store.cancel(key, now = 6_000L)
        val after = db.pendingOperationDao().getByKey(key)!!
        assertEquals("SUCCEEDED", after.state)
        assertEquals(record.trace, after.trace)
        db.close()
    }

    @Test
    fun `no duplicate install - the claimed re-probe saves one row`() =
        runBlocking {
            // (e) §1's exactly-once is about EFFECT: the blocked
            // action behind a claim is the re-probe's install,
            // which upserts by canonical identity. Running the
            // save path twice — as two racing claim winners
            // would — must still land exactly ONE source row.
            val transport = FakeTransport {
                FakeTransport.ok("<html></html>")
            }
            val repoDao = FakeExtensionRepoDao()
            val extDao = FakeInstalledExtensionDao()
            val filesDir = java.io.File(
                System.getProperty("java.io.tmpdir"),
                "dup-install-${System.nanoTime()}"
            ).apply { mkdirs() }
            val store = app.xreader.data.extension.ExtensionStore(filesDir)
            val sessionStore =
                app.xreader.data.extension.testSessionStore()
            val service = app.xreader.data.extension.ExtensionService(
                extDao, store,
                app.xreader.data.extension.HostContextFactory(
                    store, sessionStore, transport
                ),
                app.xreader.data.extension.FakeDexLoader()
            ) { app.xreader.data.extension.FakeJsRuntime() }
            val manager = app.xreader.data.extension.RepoManager(
                repoDao, extDao, store, transport, service, sessionStore
            )
            val config = app.xreader.data.extension.DeclarativeConfig(
                id = "dup-example",
                name = "Dup Example",
                lang = "en",
                baseUrl = "https://dup.example/",
                popular = app.xreader.data.extension.ListSpec(
                    url = "https://dup.example/popular",
                    results = "div.result",
                    title = "a.t",
                    link = "a.t@href"
                ),
                details = app.xreader.data.extension.DetailsSpec(
                    title = "h1",
                    chapters = app.xreader.data.extension.ChapterListSpec(
                        list = "ul.chapters > li",
                        name = "a",
                        link = "a@href"
                    )
                ),
                chapter = app.xreader.data.extension.ChapterSpec(
                    content = "article"
                )
            )
            val first = manager.saveLinkSource(config, "dup.example")
            val second = manager.saveLinkSource(config, "dup.example")
            assertTrue(first is AppResult.Ok)
            assertTrue(second is AppResult.Ok)
            assertEquals(
                (first as AppResult.Ok).value.id,
                (second as AppResult.Ok).value.id
            )
            assertEquals(
                1,
                extDao.extensions.value.count {
                    it.packageId == "link.dup-example"
                }
            )
        }

    @Test
    fun `capture completeness - cookies on a configured host are captured`() {
        // The login page lives on www.site.example; the session
        // cookie that matters sits on api.site.example, a host
        // the source's own config declares. The widened capture
        // (FIX-2, §1 residue a) must pick it up.
        val byOrigin = loginCookiesByOrigin(
            currentUrl = "https://www.site.example/login",
            originalUrl = "https://www.site.example/login",
            configuredHosts = listOf("api.site.example"),
            cookieHeaderFor = { url ->
                when {
                    url.contains("api.site.example") ->
                        "session=abc123; theme=dark"
                    url.contains("www.site.example") -> "pref=1"
                    else -> null
                }
            }
        )
        assertEquals(
            "abc123", byOrigin["api.site.example"]?.get("session")
        )
        assertNotNull(byOrigin["www.site.example"])
    }
}
