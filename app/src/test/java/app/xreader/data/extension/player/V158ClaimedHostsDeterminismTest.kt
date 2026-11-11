package app.xreader.data.extension.player

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.net.HttpTransport
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * v1.5.8 (A6 / F1 M1): the Keiyoushi host claim must not be
 * a first-materialisation race. With the catalogue cache
 * COLD (no index fetch has run this process) but an
 * installed package's persisted record carrying its source
 * hosts, [KeiyoushiPlayerManager.claimedHosts] answers
 * from the records — so a link-added declarative config on
 * that host is stamped MANGA deterministically at load
 * time, on every process start, not only after some other
 * flow happened to warm the catalogue first.
 */
class V158ClaimedHostsDeterminismTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class EmptyTransport : HttpTransport {
        override suspend fun execute(
            request: HttpTransport.Request
        ): AppResult<HttpTransport.Response> =
            AppResult.Err(
                AppError.ExtensionFailed("fake", "no body")
            )
    }

    private class FakeRecordStore : KeiyoushiRecordStore {
        var records: List<KeiyoushiPlayerRecord> = emptyList()
        override fun load(): List<KeiyoushiPlayerRecord> = records
        override fun save(records: List<KeiyoushiPlayerRecord>) {
            this.records = records
        }
    }

    private class FakeApkReader : KeiyoushiApkReader {
        override fun read(file: File): KeiyoushiApkMetadata? = null
    }

    private class FakeLoader : KeiyoushiExtensionLoader {
        override fun load(
            file: File, metadata: KeiyoushiApkMetadata
        ): List<Source> = emptyList()
    }

    private class FakeCleaner : KeiyoushiUninstallCleaner {
        override suspend fun clean(record: KeiyoushiPlayerRecord) = Unit
    }

    @Test
    fun `cold catalogue still claims installed package hosts from records`() =
        runBlocking {
            val recordStore = FakeRecordStore()
            recordStore.records = listOf(
                KeiyoushiPlayerRecord(
                    packageName = "eu.kanade.tachiyomi.extension.en.asurascans",
                    displayName = "Asura Scans",
                    status = KeiyoushiExtStatus.READY,
                    installedVersionCode = 1,
                    installedVersionName = "1.0",
                    sourceIds = listOf("keiyoushi:asura:1"),
                    sourceHosts = listOf("https://asura.example")
                )
            )
            val manager = KeiyoushiPlayerManager(
                store = ExtensionStore(File(tmp.root, "files")),
                recordStore = recordStore,
                transport = EmptyTransport(),
                apkReader = FakeApkReader(),
                loader = FakeLoader(),
                extensionDao = FakeInstalledExtensionDao(),
                repoIdProvider = { 42L },
                uninstallCleaner = FakeCleaner(),
                playerEnabledProvider = { true },
                nowMs = { 1_000L }
            )
            // Restore publishes the persisted records; NO
            // catalogue fetch runs, so the cache stays cold —
            // exactly the post-restart state F1 M1 names.
            manager.restoreInstalled()
            val claimed = manager.claimedHosts()
            assertTrue(
                "the installed package's host must be claimed " +
                    "from its record with a cold cache, got $claimed",
                "asura.example" in claimed
            )
        }

    @Test
    fun `no records and cold cache claims nothing`() = runBlocking {
        val manager = KeiyoushiPlayerManager(
            store = ExtensionStore(File(tmp.root, "files")),
            recordStore = FakeRecordStore(),
            transport = EmptyTransport(),
            apkReader = FakeApkReader(),
            loader = FakeLoader(),
            extensionDao = FakeInstalledExtensionDao(),
            repoIdProvider = { 42L },
            uninstallCleaner = FakeCleaner(),
            playerEnabledProvider = { true },
            nowMs = { 1_000L }
        )
        manager.restoreInstalled()
        assertEquals(emptySet<String>(), manager.claimedHosts())
    }
}
