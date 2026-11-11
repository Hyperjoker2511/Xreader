package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.6 (§5.5, M5) — DIAGNOSIS harness for the startup
 * extension-restore race: MainScaffold restores Keiyoushi
 * packages inside a startup coroutine while surfaces query
 * ExtensionService.loadKeiyoushiSources, which answers from
 * the player's live registry — empty while restore is in
 * flight. The question the harness must answer: does a
 * query inside that window produce a FALSE EMPTY PRESENTED
 * AS FINAL, or a transient answer that heals?
 *
 * Method: the service's keiyoushiLiveSources provider is
 * backed by a registry this test controls — empty first
 * (restore in flight), populated later (restore done) —
 * and the SAME service instance is queried across the
 * boundary with NO invalidate() call in between.
 */
class V156M5RestoreRaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "eu.kanade.tachiyomi.extension.en.fakepanels"
    private val namespaced = "keiyoushi:$pkg:777"

    private class FakeCatalogueSource : CatalogueSource {
        override val id: Long = 777L
        override val name: String = "Fake Panels"
        override val lang: String = "en"
        override val supportsLatest: Boolean = true

        override suspend fun getPopularManga(page: Int): MangasPage =
            MangasPage(
                listOf(SManga.create().apply {
                    url = "/series/one"
                    title = "Series One"
                }),
                false
            )

        override suspend fun getLatestUpdates(page: Int): MangasPage =
            MangasPage(emptyList(), false)

        override suspend fun getSearchManga(
            page: Int,
            query: String,
            filters: FilterList
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

    private fun keiyoushiRow() = InstalledExtension(
        repoId = 9,
        displayName = "Fake Panels",
        packageId = pkg,
        versionName = "1.6.5",
        versionCode = 106005,
        kind = ExtensionKind.KEIYOUSHI,
        artifactPath = null,
        sha256 = "abc",
        apiLevel = 16,
        languages = "en",
        sourceIds = "777",
        enabled = true,
        trustState = TrustState.TRUSTED,
        permissionsJson = "{}",
        installedAt = 0,
        updatedAt = 0
    )

    @Test
    fun queryDuringRestoreIsTransientAndHealsWithoutInvalidation() =
        runBlocking {
            val liveRegistry =
                AtomicReference<Map<String, eu.kanade.tachiyomi.source.Source>>(
                    emptyMap()
                )
            val extDao = FakeInstalledExtensionDao()
            val extId = extDao.insert(keiyoushiRow())
            val store = ExtensionStore(tmp.newFolder())
            val factory = HostContextFactory(
                store, testSessionStore(),
                FakeTransport { FakeTransport.ok("") }
            )
            val service = ExtensionService(
                extDao, store, factory, FakeDexLoader(),
                keiyoushiLiveSources = { liveRegistry.get() },
                keiyoushiRatingProvider = { ContentRating.SAFE }
            ) { FakeJsRuntime() }

            // ── restore IN FLIGHT: the registry is empty.
            val duringFlight = service.sourcesOf(extId)
            assertTrue(
                "an in-flight query must not fail the row",
                duringFlight is AppResult.Ok
            )
            assertEquals(
                "an in-flight query sees zero sources (the window is real)",
                0, (duringFlight as AppResult.Ok).value.size
            )
            val singleDuringFlight = service.source(extId, "777")
            assertTrue(
                "a single-source lookup inside the window answers not-found",
                singleDuringFlight is AppResult.Err
            )

            // ── restore COMPLETES: the registry populates.
            liveRegistry.set(mapOf(namespaced to FakeCatalogueSource()))

            // The decisive assertion: the SAME service
            // instance, with NO invalidate() call, now
            // returns the sources — the in-flight empty was
            // never cached or otherwise fixed as final
            // (KEIYOUSHI rows bypass the service cache by
            // design; the live registry is the authority).
            val afterRestore = service.sourcesOf(extId)
            assertTrue(afterRestore is AppResult.Ok)
            val restoredHandles = (afterRestore as AppResult.Ok).value
            assertEquals(1, restoredHandles.size)
            assertEquals(
                namespaced, restoredHandles.first().source.id
            )
            val singleAfter = service.source(extId, "777")
            assertTrue(
                "the same lookup that said not-found now resolves",
                singleAfter is AppResult.Ok
            )
        }
}
