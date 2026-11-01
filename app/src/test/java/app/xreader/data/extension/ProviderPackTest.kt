package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.3.5 (Cycle 1, provider packs): the pack system end to
 * end against fakes — schema validation, apply touching only
 * pack-managed ids, rollback restoring the previous pack
 * exactly, restart persistence through the store seam, and
 * the throttled/silent check. Fixture sources are EXAMPLE
 * sources on reserved example hosts — no real site configs
 * ship in tests.
 */
class ProviderPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repoDao: FakeExtensionRepoDao
    private lateinit var extDao: FakeInstalledExtensionDao
    private lateinit var store: ExtensionStore
    private lateinit var service: ExtensionService
    private lateinit var manager: ProviderPackManager

    private var now = 1_700_000_000_000L
    private var packBody: String = packJson(version = 1, date = DATE1, ONE, TWO)
    private var packHttpCode = 200

    private fun setUp() {
        repoDao = FakeExtensionRepoDao()
        extDao = FakeInstalledExtensionDao()
        store = ExtensionStore(tmp.newFolder())
        val sessionStore = SessionStore(testVault())
        val transport = FakeTransport { request ->
            when (request.url) {
                ProviderPackManager.PACK_URL ->
                    if (packHttpCode == 200) FakeTransport.ok(packBody)
                    else AppResult.Ok(
                        FakeTransport.response(packHttpCode, "")
                    )
                else -> AppResult.Ok(FakeTransport.response(404, ""))
            }
        }
        val contextFactory = HostContextFactory(store, sessionStore, transport)
        service = ExtensionService(
            extDao, store, contextFactory, FakeDexLoader()
        ) { FakeJsRuntime() }
        manager = ProviderPackManager(
            repoDao, extDao, store, transport, service,
            FileProviderPackStateStore(store)
        ) { now }
    }

    // ── codec ────────────────────────────────────────────────

    @Test
    fun `codec rejects unreadable json`() {
        assertTrue(ProviderPackCodec.parse("{not json") is AppResult.Err)
        assertTrue(ProviderPackCodec.parse("") is AppResult.Err)
    }

    @Test
    fun `codec rejects wrong version shapes`() {
        // version as a STRING — the shape itself is wrong.
        val stringVersion = packJson(1, DATE1, ONE)
            .replace("\"version\":1", "\"version\":\"1\"")
        assertTrue(ProviderPackCodec.parse(stringVersion) is AppResult.Err)
        // A format version this build doesn't speak.
        val futureFormat = packJson(1, DATE1, ONE)
            .replace("\"formatVersion\":1", "\"formatVersion\":99")
        assertTrue(ProviderPackCodec.parse(futureFormat) is AppResult.Err)
        // Not a pack at all.
        val wrongTag = packJson(1, DATE1, ONE)
            .replace("xreader-provider-pack", "something-else")
        assertTrue(ProviderPackCodec.parse(wrongTag) is AppResult.Err)
    }

    @Test
    fun `one bad config among good ones rejects the whole pack`() {
        val broken = packJson(1, DATE1, ONE, TWO)
            .replace("\"chapter\":{\"content\":\".text\"}", "\"chapter\":{}")
        assertTrue(ProviderPackCodec.parse(broken) is AppResult.Err)
    }

    @Test
    fun `codec rejects namespace escapes duplicates and mismatches`() {
        val escaped = packJson(1, DATE1, ONE)
            .replace(ONE, "bundled-sneaky")
        assertTrue(ProviderPackCodec.parse(escaped) is AppResult.Err)

        val duplicated = packJson(1, DATE1, ONE, ONE)
        assertTrue(ProviderPackCodec.parse(duplicated) is AppResult.Err)

        // Metadata id and config id disagree.
        val mismatched = packJson(1, DATE1, ONE)
            .replaceFirst("\"id\":\"$ONE\"", "\"id\":\"pack-renamed\"")
        assertTrue(ProviderPackCodec.parse(mismatched) is AppResult.Err)
    }

    @Test
    fun `codec accepts the fixture pack and an empty channel pack`() {
        val parsed = ProviderPackCodec.parse(packJson(3, DATE1, ONE, TWO))
        assertTrue(parsed is AppResult.Ok)
        val pack = (parsed as AppResult.Ok).value
        assertEquals("novels", pack.id)
        assertEquals(3, pack.version)
        assertEquals(listOf(ONE, TWO), pack.sources.map { it.id })

        val empty = ProviderPackCodec.parse(packJson(1, DATE1))
        assertTrue(empty is AppResult.Ok)
    }

    // ── apply / update / rollback ────────────────────────────

    @Test
    fun `apply materialises pack sources and never touches other rows`() =
        runBlocking {
            setUp()
            val userRow = seedForeignRow()
            val pack = parseOk(packJson(1, DATE1, ONE, TWO))
            val report = manager.applyPack(pack, packJson(1, DATE1, ONE, TWO))
            assertTrue(report is AppResult.Ok)

            val packRepo = repoDao.observeAll().first()
                .first { it.indexUrl == ProviderPackManager.PACK_REPO_URL }

            val one = extDao.getByPackageId(
                ProviderPackManager.packageIdFor(ONE)
            )
            assertNotNull(one)
            assertEquals(packRepo.id, one!!.repoId)
            assertEquals(TrustState.TRUSTED, one.trustState)
            assertNotNull(
                store.readText(
                    store.definitionFile(
                        ProviderPackManager.packageIdFor(ONE)
                    )
                )
            )
            assertEquals(
                "Updated by pack · $DATE1", manager.badgeFor(ONE)
            )

            // The foreign row is byte-for-byte as it was.
            val after = extDao.getById(userRow.id)
            assertEquals(userRow, after)
        }

    @Test
    fun `update then rollback restores the previous pack exactly`() =
        runBlocking {
            setUp()
            val v1 = packJson(1, DATE1, ONE, TWO)
            manager.applyPack(parseOk(v1), v1)
            val v1Fingerprint = extDao.getByPackageId(
                ProviderPackManager.packageIdFor(ONE)
            )!!.sha256

            // v2: ONE's rules change, TWO dropped, THREE added.
            val v2 = packJson(2, DATE2, ONE, THREE, oneName = "Example One Reborn")
            val report = manager.applyPack(parseOk(v2), v2)
            assertTrue(report is AppResult.Ok)
            val applied = (report as AppResult.Ok).value
            assertEquals(listOf(THREE), applied.added)
            assertEquals(listOf(ONE), applied.updated)
            assertEquals(listOf(TWO), applied.removed)
            assertNull(
                extDao.getByPackageId(
                    ProviderPackManager.packageIdFor(TWO)
                )
            )
            assertEquals(2, manager.info()!!.version)

            // Roll back: v1 returns EXACTLY — TWO restored,
            // THREE removed, ONE's rules fingerprint-identical.
            val back = manager.rollback()
            assertTrue(back is AppResult.Ok)
            assertEquals(1, manager.info()!!.version)
            assertNotNull(
                extDao.getByPackageId(
                    ProviderPackManager.packageIdFor(TWO)
                )
            )
            assertNull(
                extDao.getByPackageId(
                    ProviderPackManager.packageIdFor(THREE)
                )
            )
            assertEquals(
                v1Fingerprint,
                extDao.getByPackageId(
                    ProviderPackManager.packageIdFor(ONE)
                )!!.sha256
            )
            assertEquals(
                "Updated by pack · $DATE1", manager.badgeFor(TWO)
            )
        }

    @Test
    fun `apply refuses when a pack id is already owned another way`() =
        runBlocking {
            setUp()
            // A row squatting on the pack package id under a
            // USER repo: the pack must refuse, not adopt it.
            val userRepo = ExtensionRepo(
                name = "User repo",
                indexUrl = "https://repos.example/index.json",
                indexSha256 = null, lastFetchedAt = null, enabled = true
            ).let { it.copy(id = repoDao.insert(it)) }
            extDao.insert(
                InstalledExtension(
                    repoId = userRepo.id,
                    displayName = "Squatter",
                    packageId = ProviderPackManager.packageIdFor(ONE),
                    versionName = "1.0", versionCode = 1,
                    kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                    artifactPath = null, sha256 = "squat",
                    apiLevel = 1, languages = "en", sourceIds = ONE,
                    enabled = true, trustState = TrustState.TRUSTED,
                    permissionsJson = "{}", installedAt = 1, updatedAt = 1
                )
            )
            val v1 = packJson(1, DATE1, ONE, TWO)
            val result = manager.applyPack(parseOk(v1), v1)
            assertTrue(result is AppResult.Err)
            assertNull("a refused pack records no state", manager.info())
            assertEquals(
                "Squatter",
                extDao.getByPackageId(
                    ProviderPackManager.packageIdFor(ONE)
                )!!.displayName
            )
        }

    // ── persistence + check ──────────────────────────────────

    @Test
    fun `pack state survives a restart through the store seam`() =
        runBlocking {
            setUp()
            val v1 = packJson(1, DATE1, ONE, TWO)
            manager.applyPack(parseOk(v1), v1)

            // A brand-new manager over the same store = a
            // restarted app.
            val restarted = ProviderPackManager(
                repoDao, extDao, store,
                FakeTransport { AppResult.Ok(FakeTransport.response(404, "")) },
                service, FileProviderPackStateStore(store)
            ) { now }
            val info = restarted.info()
            assertNotNull(info)
            assertEquals(1, info!!.version)
            assertEquals(listOf(ONE, TWO), info.sourceIds)
            assertEquals("Updated by pack · $DATE1", restarted.badgeFor(ONE))
            assertNull(restarted.badgeFor("bundled-novelbuddy"))
            assertNull(restarted.badgeFor("pack-not-in-the-pack"))
        }

    @Test
    fun `check applies a newer pack, throttles, and fails silently`() =
        runBlocking {
            setUp()
            val v1 = packJson(1, DATE1, ONE, TWO)
            manager.applyPack(parseOk(v1), v1)

            // A newer pack is published → the manual check applies it.
            packBody = packJson(2, DATE2, ONE, TWO)
            val updated = manager.checkForUpdates(manual = true)
            assertTrue(updated is PackCheckOutcome.Updated)
            assertEquals(2, manager.info()!!.version)

            // The automatic check inside a day is throttled.
            val throttled = manager.checkForUpdates(manual = false)
            assertTrue(throttled is PackCheckOutcome.SkippedThrottled)

            // A day later with the server down: silent failure,
            // the applied pack untouched.
            now += ProviderPackManager.AUTO_CHECK_INTERVAL_MS + 1
            packHttpCode = 503
            val failed = manager.checkForUpdates(manual = false)
            assertTrue(failed is PackCheckOutcome.Failed)
            assertEquals(2, manager.info()!!.version)
            assertEquals(
                "Updated by pack · $DATE2", manager.badgeFor(ONE)
            )
        }

    // ── fixtures ─────────────────────────────────────────────

    private fun parseOk(raw: String): ProviderPack =
        (ProviderPackCodec.parse(raw) as AppResult.Ok).value

    private suspend fun seedForeignRow(): InstalledExtension {
        val repo = ExtensionRepo(
            name = "User repo",
            indexUrl = "https://repos.example/index.json",
            indexSha256 = null, lastFetchedAt = null, enabled = true
        ).let { it.copy(id = repoDao.insert(it)) }
        val row = InstalledExtension(
            repoId = repo.id,
            displayName = "A user's own source",
            packageId = "user.own-source",
            versionName = "1.0", versionCode = 7,
            kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
            artifactPath = null, sha256 = "userhash",
            apiLevel = 1, languages = "en", sourceIds = "own-source",
            enabled = false, trustState = TrustState.TRUSTED,
            permissionsJson = "{}", installedAt = 5, updatedAt = 5
        )
        return row.copy(id = extDao.insert(row))
    }

    companion object {
        private const val ONE = "pack-example-one"
        private const val TWO = "pack-example-two"
        private const val THREE = "pack-example-three"
        private const val DATE1 = "2026-11-01"
        private const val DATE2 = "2026-12-01"

        private fun configJson(id: String, name: String, host: String): String =
            """{"id":"$id","name":"$name","lang":"en",""" +
                """"baseUrl":"https://$host",""" +
                """"details":{"title":"h1","chapters":{"list":".chapters li",""" +
                """"name":"a","link":"a@href"}},"chapter":{"content":".text"}}"""

        private fun sourceJson(id: String, name: String, host: String): String =
            """{"id":"$id","name":"$name","lang":"en",""" +
                """"baseUrl":"https://$host","config":${configJson(id, name, host)}}"""

        private fun packJson(
            version: Int,
            date: String,
            vararg sourceIds: String,
            oneName: String = "Example One"
        ): String {
            val names = mapOf(
                ONE to oneName, TWO to "Example Two",
                THREE to "Example Three"
            )
            val hosts = mapOf(
                ONE to "one.example", TWO to "two.example",
                THREE to "three.example"
            )
            val sources = sourceIds.joinToString(",") { id ->
                sourceJson(id, names.getValue(id), hosts.getValue(id))
            }
            return """{"format":"xreader-provider-pack","formatVersion":1,""" +
                """"id":"novels","version":$version,"date":"$date",""" +
                """"sources":[$sources]}"""
        }
    }
}
