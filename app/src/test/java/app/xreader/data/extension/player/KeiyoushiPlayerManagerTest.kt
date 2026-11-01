package app.xreader.data.extension.player

import app.xreader.core.error.AppResult
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.Hashes
import app.xreader.data.extension.KeiyoushiIndex
import app.xreader.data.net.HttpTransport
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * K3: the player manager end-to-end against fakes — the
 * honest JVM half of the fixture strategy. Real DEX loading
 * is NOT exercised here (it cannot be, on a JVM): the loader
 * is a fake that returns in-process sources, which proves
 * the download → verify → consent → register flow, the
 * consent-before-load ordering, the update rollback and the
 * uninstall cascade. The device proof is K6's smoke list.
 */
class KeiyoushiPlayerManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "eu.kanade.tachiyomi.extension.en.fakepanels"
    private val key = KeiyoushiTrust.EXPECTED_SIGNING_KEY
    private val apkBytesV1 = "fake-apk-bytes-v1".toByteArray()
    private val apkBytesV2 = "fake-apk-bytes-v2".toByteArray()

    private lateinit var store: ExtensionStore
    private lateinit var recordStore: FakeRecordStore
    private lateinit var transport: FakeTransport
    private lateinit var apkReader: FakeApkReader
    private lateinit var loader: FakeLoader
    private lateinit var dao: FakeDao
    private lateinit var cleaner: FakeCleaner
    private lateinit var manager: KeiyoushiPlayerManager

    private fun indexBody(
        versionCode: String = "106005",
        versionName: String = "1.6.5",
        signingKey: String = key
    ) = """
        {
          "name": "Keiyoushi",
          "signingKey": "$signingKey",
          "extensionList": {"extensions": [
            {"name": "Fake Panels", "packageName": "$pkg",
             "contentWarning": "CONTENT_WARNING_SAFE",
             "extensionLib": "1.6",
             "versionCode": "$versionCode",
             "versionName": "$versionName",
             "resources": {"apkUrl": "https://example.test/fake.apk"},
             "sources": [
               {"id": "777", "name": "Fake Panels", "language": "en",
                "homeUrl": "https://fakepanels.example"}
             ]}
          ]}
        }
    """.trimIndent()

    private fun metadata(
        versionName: String = "1.6.5",
        versionCode: Long = 106005,
        certs: List<String> = listOf(key),
        lib: Float = 1.6f
    ) = KeiyoushiApkMetadata(
        packageName = pkg,
        label = "Fake Panels",
        versionName = versionName,
        versionCode = versionCode,
        entryClass = "keiyoushi.source.Generated",
        pkgFactory = null,
        extensionLib = lib,
        isNsfw = false,
        certSha256s = certs
    )

    @Before
    fun setUp() {
        store = ExtensionStore(File(tmp.root, "files"))
        recordStore = FakeRecordStore()
        transport = FakeTransport()
        transport.bodies[KeiyoushiIndex.INDEX_URL] =
            indexBody().toByteArray()
        transport.bodies["https://example.test/fake.apk"] = apkBytesV1
        apkReader = FakeApkReader(metadata())
        loader = FakeLoader(listOf(FakeSource()))
        dao = FakeDao()
        cleaner = FakeCleaner()
        manager = newManager()
    }

    private fun newManager(
        playerEnabledProvider: suspend () -> Boolean = { true }
    ) = KeiyoushiPlayerManager(
        store = store,
        recordStore = recordStore,
        transport = transport,
        apkReader = apkReader,
        loader = loader,
        extensionDao = dao,
        repoIdProvider = { 42L },
        uninstallCleaner = cleaner,
        playerEnabledProvider = playerEnabledProvider,
        nowMs = { 1_000L }
    )

    private fun installV1(): KeiyoushiPlayerRecord = runBlocking {
        val staged = manager.beginInstall(pkg)
        assertTrue(staged is AppResult.Ok)
        val prompt = manager.consentPrompt(pkg)
        assertNotNull(prompt)
        val done = manager.confirmInstall(pkg, prompt!!.fingerprint)
        assertTrue(done is AppResult.Ok)
        (done as AppResult.Ok).value
    }

    @Test
    fun `consent comes before any load`() = runBlocking {
        val staged = manager.beginInstall(pkg)
        assertTrue(staged is AppResult.Ok)
        val record = (staged as AppResult.Ok).value
        assertEquals(
            KeiyoushiExtStatus.AWAITING_CONSENT, record.status
        )
        // The loader has NOT run — consent is the gate.
        assertEquals(0, loader.calls)
        assertNull(manager.sourceById("keiyoushi:$pkg:777"))
        // The prompt carries the verified fingerprint + signer.
        val prompt = manager.consentPrompt(pkg)!!
        assertEquals(Hashes.sha256Hex(apkBytesV1), prompt.fingerprint)
        assertEquals(key, prompt.signerSha256)
        assertTrue(prompt.summary.contains("not sandboxed"))
    }

    @Test
    fun `confirm with the wrong fingerprint loads nothing`() =
        runBlocking {
            manager.beginInstall(pkg)
            val result = manager.confirmInstall(pkg, "0".repeat(64))
            assertTrue(result is AppResult.Err)
            assertEquals(0, loader.calls)
            assertEquals(
                KeiyoushiExtStatus.AWAITING_CONSENT,
                manager.record(pkg)!!.status
            )
        }

    @Test
    fun `a confirmed install loads registers and mirrors a row`() {
        val record = installV1()
        assertEquals(KeiyoushiExtStatus.READY, record.status)
        assertEquals(1, loader.calls)
        assertNotNull(manager.sourceById("keiyoushi:$pkg:777"))
        assertEquals(
            listOf("keiyoushi:$pkg:777"), record.sourceIds
        )
        // The installed file is in place; the staging is gone.
        assertTrue(store.keiyoushiExtFile(pkg).exists())
        assertFalse(store.keiyoushiStagingFile(pkg).exists())
        // The mirroring row: KEIYOUSHI kind, consent recorded
        // as the DEX opt-in, enabled, trusted.
        val row = dao.rows.single()
        assertEquals(
            app.xreader.data.db.ExtensionKind.KEIYOUSHI, row.kind
        )
        assertEquals(TrustState.TRUSTED, row.trustState)
        assertTrue(row.enabled)
        assertEquals(106005, row.versionCode)
        assertTrue(
            ExtensionPermissions.parse(row.permissionsJson).dexOptIn
        )
        // The row state for the shelf reads READY.
        assertEquals(
            KeiyoushiIndex.PlayerPackageState.Kind.READY,
            manager.packageStates.value[pkg]!!.kind
        )
    }

    @Test
    fun `a foreign signer never reaches consent or load`() =
        runBlocking {
            apkReader.metadata = metadata(certs = listOf("d".repeat(64)))
            val result = manager.beginInstall(pkg)
            assertTrue(result is AppResult.Ok)
            val record = (result as AppResult.Ok).value
            assertEquals(KeiyoushiExtStatus.FAILED, record.status)
            assertTrue(
                record.failureReason!!.contains("different key")
            )
            assertEquals(0, loader.calls)
            assertNull(manager.consentPrompt(pkg))
            assertTrue(dao.rows.isEmpty())
        }

    @Test
    fun `a swapped index key refuses before download`() = runBlocking {
        transport.bodies[KeiyoushiIndex.INDEX_URL] =
            indexBody(signingKey = "e".repeat(64)).toByteArray()
        val result = manager.beginInstall(pkg)
        assertTrue(result is AppResult.Err)
        assertEquals(0, loader.calls)
    }

    @Test
    fun `a lib 1_4 package is incompatible not failed`() = runBlocking {
        apkReader.metadata = metadata(lib = 1.4f)
        val result = manager.beginInstall(pkg)
        assertTrue(result is AppResult.Ok)
        val record = (result as AppResult.Ok).value
        assertEquals(KeiyoushiExtStatus.INCOMPATIBLE, record.status)
        assertTrue(record.failureReason!!.contains("1.4"))
        assertEquals(0, loader.calls)
    }

    @Test
    fun `a script engine load failure names the gap`() = runBlocking {
        loader.failure = UnsupportedOperationException(
            "JavaScriptEngine is not supported by Xreader's Keiyoushi player"
        )
        manager.beginInstall(pkg)
        val prompt = manager.consentPrompt(pkg)!!
        val result = manager.confirmInstall(pkg, prompt.fingerprint)
        assertTrue(result is AppResult.Ok)
        val record = (result as AppResult.Ok).value
        assertEquals(KeiyoushiExtStatus.FAILED, record.status)
        assertTrue(
            record.failureReason!!.contains("script engine")
        )
        assertNull(manager.sourceById("keiyoushi:$pkg:777"))
    }

    @Test
    fun `an okhttp load failure names the gate step`() = runBlocking {
        loader.failure =
            NoClassDefFoundError("okhttp3/CompressionInterceptor")
        manager.beginInstall(pkg)
        val prompt = manager.consentPrompt(pkg)!!
        val result = manager.confirmInstall(pkg, prompt.fingerprint)
        val record = (result as AppResult.Ok).value
        assertEquals(KeiyoushiExtStatus.FAILED, record.status)
        assertTrue(record.failureReason!!.contains("G-K2-1"))
    }

    @Test
    fun `an update flags stages and lands on the same row`() =
        runBlocking {
            installV1()
            val rowId = dao.rows.single().id
            // The catalogue moves to 1.6.71.
            transport.bodies[KeiyoushiIndex.INDEX_URL] = indexBody(
                versionCode = "106071", versionName = "1.6.71"
            ).toByteArray()
            transport.bodies["https://example.test/fake.apk"] =
                apkBytesV2
            apkReader.metadata = metadata(
                versionName = "1.6.71", versionCode = 106071
            )
            val flagged = manager.checkForUpdates()
            assertTrue(flagged is AppResult.Ok)
            assertEquals(1, (flagged as AppResult.Ok).value)
            assertEquals(
                KeiyoushiIndex.PlayerPackageState.Kind.UPDATE_AVAILABLE,
                manager.packageStates.value[pkg]!!.kind
            )
            val staged = manager.beginInstall(pkg)
            assertTrue(staged is AppResult.Ok)
            val prompt = manager.consentPrompt(pkg)!!
            assertEquals(Hashes.sha256Hex(apkBytesV2), prompt.fingerprint)
            val done = manager.confirmInstall(pkg, prompt.fingerprint)
            val record = (done as AppResult.Ok).value
            assertEquals(KeiyoushiExtStatus.READY, record.status)
            assertEquals(106071, record.installedVersionCode)
            // Same row, updated in place — books keep their link.
            assertEquals(rowId, dao.rows.single().id)
            assertEquals(106071, dao.rows.single().versionCode)
            assertEquals(
                apkBytesV2.toList(),
                store.keiyoushiExtFile(pkg).readBytes().toList()
            )
        }

    @Test
    fun `a broken update rolls back to the working version`() =
        runBlocking {
            installV1()
            transport.bodies[KeiyoushiIndex.INDEX_URL] = indexBody(
                versionCode = "106071", versionName = "1.6.71"
            ).toByteArray()
            transport.bodies["https://example.test/fake.apk"] =
                apkBytesV2
            apkReader.metadata = metadata(
                versionName = "1.6.71", versionCode = 106071
            )
            loader.failure = IllegalStateException("v2 is broken")
            manager.beginInstall(pkg)
            val prompt = manager.consentPrompt(pkg)!!
            val result = manager.confirmInstall(pkg, prompt.fingerprint)
            val record = (result as AppResult.Ok).value
            assertEquals(KeiyoushiExtStatus.READY, record.status)
            assertEquals(106005, record.installedVersionCode)
            assertEquals(
                Hashes.sha256Hex(apkBytesV1), record.apkSha256
            )
            assertEquals(
                apkBytesV1.toList(),
                store.keiyoushiExtFile(pkg).readBytes().toList()
            )
            assertTrue(
                record.failureReason!!.contains("is back")
            )
        }

    @Test
    fun `uninstall runs the whole cascade`() = runBlocking {
        installV1()
        manager.uninstall(pkg)
        assertNull(manager.record(pkg))
        assertNull(manager.sourceById("keiyoushi:$pkg:777"))
        assertFalse(store.keiyoushiExtFile(pkg).exists())
        assertEquals(1, cleaner.cleaned.size)
        assertEquals(pkg, cleaner.cleaned.single().packageName)
        assertTrue(dao.rows.isEmpty())
        assertTrue(recordStore.records.isEmpty())
    }

    @Test
    fun `restore reloads a consented install`() = runBlocking {
        installV1()
        val manager2 = newManager()
        manager2.restoreInstalled()
        val record = manager2.record(pkg)!!
        assertEquals(KeiyoushiExtStatus.READY, record.status)
        assertNotNull(manager2.sourceById("keiyoushi:$pkg:777"))
    }

    @Test
    fun `restore freezes a tampered file and asks for re-trust`() =
        runBlocking {
            installV1()
            val file = store.keiyoushiExtFile(pkg)
            file.setWritable(true)
            file.writeBytes("tampered".toByteArray())
            val callsBefore = loader.calls
            val manager2 = newManager()
            manager2.restoreInstalled()
            val record = manager2.record(pkg)!!
            assertEquals(KeiyoushiExtStatus.FAILED, record.status)
            assertTrue(
                record.failureReason!!.contains("changed since it was installed")
            )
            // The tampered bytes were never loaded.
            assertEquals(callsBefore, loader.calls)
            assertEquals(
                TrustState.CHANGED_REQUIRES_RETRUST,
                dao.rows.single().trustState
            )
        }

    // ── K6: crash safety + kill switch ──────────────────────

    @Test
    fun `the in-flight marker is armed during the load and disarmed after`() =
        runBlocking {
            var armedDuringLoad: Boolean? = null
            loader.onLoad = {
                armedDuringLoad =
                    recordStore.records.single().loadInFlight
            }
            val record = installV1()
            assertEquals(true, armedDuringLoad)
            assertFalse(record.loadInFlight)
            assertFalse(recordStore.records.single().loadInFlight)
        }

    @Test
    fun `restore quarantines a package whose load was in flight`() =
        runBlocking {
            installV1()
            // The process died while this package was
            // starting: its persisted record still carries
            // the armed marker (and the LOADING status the
            // load had published).
            recordStore.records = recordStore.records.map {
                it.copy(
                    loadInFlight = true,
                    status = KeiyoushiExtStatus.LOADING
                )
            }
            val callsBefore = loader.calls
            val manager2 = newManager()
            manager2.restoreInstalled()
            val record = manager2.record(pkg)!!
            assertEquals(KeiyoushiExtStatus.FAILED, record.status)
            assertTrue(
                record.failureReason!!.contains("stopped Xreader")
            )
            assertFalse(record.loadInFlight)
            // Never retried on its own — no load was attempted.
            assertEquals(callsBefore, loader.calls)
            assertNull(manager2.sourceById("keiyoushi:$pkg:777"))
        }

    @Test
    fun `restore never retries a failed package`() = runBlocking {
        installV1()
        recordStore.records = recordStore.records.map {
            it.copy(
                status = KeiyoushiExtStatus.FAILED,
                failureReason = "it couldn't be started (IllegalStateException)"
            )
        }
        val callsBefore = loader.calls
        val manager2 = newManager()
        manager2.restoreInstalled()
        val record = manager2.record(pkg)!!
        assertEquals(KeiyoushiExtStatus.FAILED, record.status)
        assertEquals(callsBefore, loader.calls)
        assertNull(manager2.sourceById("keiyoushi:$pkg:777"))
    }

    @Test
    fun `a hard load error at restore quarantines a proven package`() =
        runBlocking {
            installV1()
            loader.failure =
                NoClassDefFoundError("okhttp3/CompressionInterceptor")
            val manager2 = newManager()
            manager2.restoreInstalled()
            val record = manager2.record(pkg)!!
            // FAILED — not READY-with-a-notice: a hard death
            // is quarantined even though v1 once ran.
            assertEquals(KeiyoushiExtStatus.FAILED, record.status)
            assertTrue(record.failureReason!!.contains("G-K2-1"))
            assertNull(manager2.sourceById("keiyoushi:$pkg:777"))
        }

    @Test
    fun `an ordinary load error at restore keeps a proven package ready`() =
        runBlocking {
            installV1()
            loader.failure = IllegalStateException("reader moved")
            val manager2 = newManager()
            manager2.restoreInstalled()
            val record = manager2.record(pkg)!!
            assertEquals(KeiyoushiExtStatus.READY, record.status)
            assertTrue(
                record.failureReason!!.contains("couldn't be started")
            )
            assertTrue(
                record.failureDetail!!.contains("reader moved")
            )
            assertNull(manager2.sourceById("keiyoushi:$pkg:777"))
        }

    @Test
    fun `the kill switch blocks restore loads and empties the registry`() =
        runBlocking {
            installV1()
            val callsBefore = loader.calls
            val manager2 = newManager(playerEnabledProvider = { false })
            manager2.restoreInstalled()
            // Nothing loaded while the switch is off.
            assertEquals(callsBefore, loader.calls)
            assertFalse(manager2.isPlayerEnabled())
            assertNull(manager2.sourceById("keiyoushi:$pkg:777"))
            assertTrue(manager2.liveSourcesOf(pkg).isEmpty())
            assertTrue(manager2.allLiveSources().isEmpty())
            // Turning it on loads the installed packages again.
            manager2.setPlayerEnabled(true)
            assertTrue(manager2.isPlayerEnabled())
            assertEquals(callsBefore + 1, loader.calls)
            assertNotNull(manager2.sourceById("keiyoushi:$pkg:777"))
            // And off empties the registry at once.
            manager2.setPlayerEnabled(false)
            assertNull(manager2.sourceById("keiyoushi:$pkg:777"))
            assertTrue(manager2.allLiveSources().isEmpty())
        }

    // ── fakes ───────────────────────────────────────────────

    private class FakeSource(
        override val id: Long = 777L,
        override val name: String = "Fake Panels",
        override val lang: String = "en"
    ) : CatalogueSource {
        override val supportsLatest: Boolean = true
        override suspend fun getPopularManga(page: Int): MangasPage =
            MangasPage(emptyList(), false)

        override suspend fun getLatestUpdates(page: Int): MangasPage =
            MangasPage(emptyList(), false)

        override suspend fun getSearchManga(
            page: Int, query: String, filters: FilterList
        ): MangasPage = MangasPage(emptyList(), false)

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean
        ): SMangaUpdate = SMangaUpdate(manga, chapters)

        override suspend fun getPageList(
            chapter: SChapter
        ): List<Page> = emptyList()
    }

    private class FakeTransport : HttpTransport {
        val bodies = mutableMapOf<String, ByteArray>()
        override suspend fun execute(
            request: HttpTransport.Request
        ): AppResult<HttpTransport.Response> {
            val bytes = bodies[request.url]
                ?: return AppResult.Err(
                    app.xreader.core.error.AppError.ExtensionFailed(
                        "fake transport", "no body for ${request.url}"
                    )
                )
            return AppResult.Ok(
                HttpTransport.Response(
                    code = 200,
                    headers = emptyMap(),
                    body = ByteArrayInputStream(bytes),
                    isPartialContent = false
                )
            )
        }
    }

    private class FakeApkReader(
        var metadata: KeiyoushiApkMetadata?
    ) : KeiyoushiApkReader {
        override fun read(file: File): KeiyoushiApkMetadata? = metadata
    }

    private class FakeLoader(
        var sources: List<eu.kanade.tachiyomi.source.Source>
    ) : KeiyoushiExtensionLoader {
        var calls = 0
        var failure: Throwable? = null

        /** Runs inside the load, before the failure — the
         * in-flight marker test reads the persisted record
         * here to prove it was armed before the loader ran. */
        var onLoad: (() -> Unit)? = null

        override fun load(
            file: File, metadata: KeiyoushiApkMetadata
        ): List<eu.kanade.tachiyomi.source.Source> {
            calls++
            onLoad?.invoke()
            failure?.let { throw it }
            return sources
        }
    }

    private class FakeRecordStore : KeiyoushiRecordStore {
        var records: List<KeiyoushiPlayerRecord> = emptyList()
        override fun load(): List<KeiyoushiPlayerRecord> = records
        override fun save(records: List<KeiyoushiPlayerRecord>) {
            this.records = records
        }
    }

    private class FakeCleaner : KeiyoushiUninstallCleaner {
        val cleaned = mutableListOf<KeiyoushiPlayerRecord>()
        override suspend fun clean(record: KeiyoushiPlayerRecord) {
            cleaned += record
        }
    }

    private class FakeDao : InstalledExtensionDao {
        val rows = mutableListOf<InstalledExtension>()
        private var nextId = 1L
        override suspend fun insert(
            extension: InstalledExtension
        ): Long {
            val id = nextId++
            rows += extension.copy(id = id)
            return id
        }

        override suspend fun update(extension: InstalledExtension) {
            val index = rows.indexOfFirst { it.id == extension.id }
            if (index >= 0) rows[index] = extension
        }

        override suspend fun delete(extension: InstalledExtension) {
            rows.removeAll { it.id == extension.id }
        }

        override suspend fun getByPackageId(
            packageId: String
        ): InstalledExtension? =
            rows.firstOrNull { it.packageId == packageId }

        override suspend fun getById(id: Long): InstalledExtension? =
            rows.firstOrNull { it.id == id }

        override fun observeById(
            id: Long
        ): Flow<InstalledExtension?> = flowOf(getByIdBlocking(id))

        private fun getByIdBlocking(id: Long): InstalledExtension? =
            rows.firstOrNull { it.id == id }

        override fun observeAll():
            Flow<List<InstalledExtension>> =
            MutableStateFlow(rows.toList())

        override suspend fun countAll(): Int = rows.size

        override suspend fun allNow(): List<InstalledExtension> =
            rows.toList()
    }
}
