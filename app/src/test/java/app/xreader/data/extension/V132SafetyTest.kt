package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Chapter
import app.xreader.data.db.InstalledExtension
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.3.2 safety pins:
 *  - item 84: failures NEVER switch a source off (the v1.3.1
 *    circuit-breaker is deleted, not softened — this test is
 *    its grave marker);
 *  - item 62: the dedupe name-matched collapse and its guards;
 *  - items 64/78b (scope B/R3): a completed list settles —
 *    form-variant duplicates drop, dates backfill.
 */
class V132SafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Item 84: no auto-off, ever ────────────────────────────

    @Test
    fun `repeated failures never switch the source off`() = runBlocking {
        val extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val failing = FakeTransport {
            AppResult.Err(
                AppError.ProviderDown(
                    "fixture", detail = "the source failed with HTTP 500"
                )
            )
        }
        val factory = HostContextFactory(
            store, testSessionStore(), failing
        )
        val service = ExtensionService(
            extDao, store, factory, FakeDexLoader()
        ) { FakeJsRuntime() }
        store.writeText(
            store.definitionFile("test.novels"),
            DeclarativeFingerprints.encode(listOf(FixtureSite.config()))
        )
        val extId = extDao.insert(
            InstalledExtension(
                repoId = 1,
                displayName = "Novels Example",
                packageId = "test.novels",
                versionName = "1.0",
                versionCode = 1,
                kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                artifactPath = null,
                sha256 = DeclarativeFingerprints.of(
                    listOf(FixtureSite.config())
                ),
                apiLevel = 1,
                languages = "en",
                sourceIds = "novels-example",
                installedAt = 0,
                updatedAt = 0,
                permissionsJson = kotlinx.serialization.json.Json
                    .encodeToString(
                        ExtensionPermissions.serializer(),
                        ExtensionPermissions(
                            domains = listOf(FixtureSite.HOST)
                        )
                    )
            )
        )
        val handle = service.allSources().first { it.extension.id == extId }
        // The old breaker retired a source at THREE failures.
        // Five in a row must change nothing but the error.
        repeat(5) {
            val result = service.guarded(handle) { it.popular(1) }
            assertTrue(result is AppResult.Err)
        }
        val row = extDao.extensions.value.first { it.id == extId }
        assertTrue(
            "a source is off only because its reader turned it off",
            row.enabled
        )
    }

    // ── Item 62: dedupe pass 2 ────────────────────────────────

    private fun dedupeRow(
        id: Long,
        pkg: String,
        name: String,
        domain: String?,
        enabled: Boolean = true,
        readable: Boolean = true,
        installedAt: Long = id
    ) = SourceDedupePlanner.Row(
        extensionId = id,
        packageId = pkg,
        displayName = name,
        origin = "test",
        canonicalDomain = domain,
        sourceIds = listOf("s$id"),
        installedAt = installedAt,
        enabled = enabled,
        readable = readable
    )

    @Test
    fun `disabled ungroupable name twin collapses into the bundled row`() {
        val bundled = dedupeRow(
            1, "bundled.novelbuddy", "NovelBuddy", "novelbuddy.me"
        )
        val leftover = dedupeRow(
            2, "link.abc", "NovelBuddy", domain = null,
            enabled = false, readable = false
        )
        val plan = SourceDedupePlanner.plan(listOf(bundled, leftover))
        assertEquals(1, plan.size)
        assertEquals(2L, plan.first().removed.extensionId)
        assertEquals(1L, plan.first().survivor.extensionId)
    }

    @Test
    fun `readable enabled mirror on its own domain is never merged`() {
        val bundled = dedupeRow(
            1, "bundled.novelbuddy", "NovelBuddy", "novelbuddy.me"
        )
        // The .com mirror: reads fine, switched on, distinct
        // registrable domain — the mirror rule stands.
        val mirror = dedupeRow(
            2, "link.def", "NovelBuddy", "novelbuddy.com",
            enabled = true, readable = true
        )
        val plan = SourceDedupePlanner.plan(listOf(bundled, mirror))
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `domain duplicates still collapse as before`() {
        val bundled = dedupeRow(
            1, "bundled.x", "Site X", "x.example", installedAt = 5
        )
        val dupe = dedupeRow(
            2, "link.x", "Site X", "x.example", installedAt = 9
        )
        val plan = SourceDedupePlanner.plan(listOf(bundled, dupe))
        assertEquals(1, plan.size)
        assertEquals(1L, plan.first().survivor.extensionId)
    }

    // ── Scope B/R3: completion settles the stored list ────────

    private fun storedChapter(
        id: Long,
        index: Int,
        number: Int,
        remoteId: String,
        dateText: String? = null
    ) = Chapter(
        id = id,
        bookId = 7L,
        index = index,
        title = "Chapter $number",
        remoteId = remoteId,
        updatedAt = 0L,
        dateText = dateText
    )

    @Test
    fun `completion drops form-variant duplicates and backfills dates`() {
        val base = "https://novelbuddy.me/shadow-slave"
        // Stored: the API-form rows 1..5 (chapter 3 lost its
        // date somewhere) PLUS legacy www/trailing-slash
        // duplicates of chapters 1 and 2 (the +24 shape).
        val existing = (1..5).map { n ->
            storedChapter(
                id = n.toLong(),
                index = n - 1,
                number = n,
                remoteId = "$base/chapter-$n",
                dateText = if (n == 3) null else "2026-10-29"
            )
        } + listOf(
            storedChapter(
                id = 101, index = 5, number = 1,
                remoteId = "https://www.novelbuddy.me/shadow-slave/chapter-1/"
            ),
            storedChapter(
                id = 102, index = 6, number = 2,
                remoteId = "https://www.novelbuddy.me/shadow-slave/chapter-2/"
            )
        )
        val fetched = (1..5).map { n ->
            SourceChapter(
                id = "$base/chapter-$n",
                name = "Chapter $n",
                number = n.toFloat(),
                dateText = "2026-10-29"
            )
        }
        val plan = ChapterReconciler.reconcile(existing, fetched)
        assertEquals(
            "both form-variant duplicates drop",
            2, plan.dropped.size
        )
        assertTrue(plan.keptStale.isEmpty())
        assertTrue(plan.inserts.isEmpty())
        // The survivors after the plan: updates only, one per
        // fetched chapter.
        assertEquals(5, plan.updates.size)
        // Item 78b: chapter 3's missing date backfills from
        // the fetched row.
        val chapter3 = plan.updates.first {
            it.newRemoteId == "$base/chapter-3"
        }
        assertEquals("2026-10-29", chapter3.newDateText)
    }
}
