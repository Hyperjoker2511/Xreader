package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.HostStore
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.1.0 (Nocturne reskin design §6 + §9 pins) for Worker 2's
 * batch:
 *
 * - Fix 1 (Shape B): the chikari lane's Book-Shape Rule —
 *   comment anchors (chapter-shaped, avatar imgs) yield ZERO
 *   catalogue items; every book card survives; the production
 *   lane config and the hand-duplicated test mirror agree.
 * - Fix 4: Live derivation admits books only, titles come
 *   from the cover img@alt, and the v2 shape cache never
 *   serves a stale v1 entry.
 * - Fix 5: the rebuildable helper's truth table.
 * - §6.5b: a no-search-by-design source's Health verdict is
 *   not dragged to failure by the search line.
 *
 * Fixtures use synthetic prose/cards only.
 */
class V110ChikariLiveFixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun chikariContext(page: String): app.xreader.extensionapi.SourceContext {
        val transport = FakeTransport { request ->
            if (request.url.startsWith(ChikariSite.BASE)) {
                FakeTransport.ok(page)
            } else {
                AppResult.Ok(FakeTransport.response(404, "not found"))
            }
        }
        return testContext(
            transport, tmp.newFolder(),
            permissions = ExtensionPermissions(
                domains = listOf(ChikariSite.HOST)
            )
        )
    }

    private fun laneConfig(): DeclarativeConfig {
        val entry = KnownSites.entryFor(ChikariSite.HOST)!!
        return KnownSites.fullConfig(entry, "chikari", "Chikari", ChikariSite.BASE)!!
    }

    /** The V106StageBTest hand mirror's shape, rebuilt here so
     * lane and mirror are pinned against the SAME fixture. */
    private fun mirrorConfig(): DeclarativeConfig = DeclarativeConfig(
        id = "chikari",
        name = "Chikari",
        baseUrl = ChikariSite.BASE,
        popular = ListSpec(
            url = ChikariSite.BASE,
            results = "a[href*='/novels/']",
            title = "img@alt",
            link = "@href",
            cover = "img@src",
            linkPattern = "/novels/[^/]+$"
        ),
        details = DetailsSpec(
            title = "h1",
            chapters = ChapterListSpec(
                list = "a[href~=/novels/[^/]+/[0-9]+$]",
                name = "@text",
                link = "@href",
                reverse = true
            )
        ),
        chapter = ChapterSpec(content = "article"),
        needsRendering = true
    )

    // ── Fix 1: comment cards are not books ──────────────────

    @Test
    fun `lane catalogue drops comment cards and keeps every book card`() {
        val source = DeclarativeSource(
            laneConfig(), chikariContext(ChikariSite.RENDERED_HOME_WITH_COMMENTS)
        )
        val page = source.popular(1)
        assertEquals(
            listOf("Shadow Slave", "Lord of Ash", "Dim Harbour"),
            page.books.map { it.title }
        )
        assertTrue(
            page.books.none {
                it.id.contains("/novels/shadow-slave/") ||
                    it.title.startsWith("Reader")
            }
        )
        // Titles came from the cover img@alt — the first book's
        // cover is its own poster, never a commenter avatar.
        assertEquals(
            "https://cdn.chikari.moe/novels/53/cover.webp",
            page.books.first().coverUrl
        )
    }

    @Test
    fun `test mirror agrees with the lane on the comment fixture`() {
        val page = DeclarativeSource(
            mirrorConfig(), chikariContext(ChikariSite.RENDERED_HOME_WITH_COMMENTS)
        ).popular(1)
        assertEquals(
            listOf("Shadow Slave", "Lord of Ash", "Dim Harbour"),
            page.books.map { it.title }
        )
        assertEquals(
            laneConfig().popular?.linkPattern,
            mirrorConfig().popular?.linkPattern
        )
    }

    @Test
    fun `book hrefs with trailing slash or query still count as books`() {
        // Shape B's reason to exist: the normalised path is
        // matched, so a dirty-but-book href survives while a
        // chapter-shaped one (also dirty) still drops.
        val dirtyHome = """
            <html><body><main>
              <a href="/novels/shadow-slave/?from=home#top"><img src="/covers/s.jpg" alt="Shadow Slave"/></a>
              <a href="/novels/lord-of-ash?from=home"><img src="/covers/a.jpg" alt="Lord of Ash"/></a>
              <a href="/novels/shadow-slave/214/?from=comment"><img src="/avatars/r.png" alt="Reader One"/></a>
            </main></body></html>
        """.trimIndent()
        val page = DeclarativeSource(
            laneConfig(), chikariContext(dirtyHome)
        ).popular(1)
        assertEquals(
            listOf("Shadow Slave", "Lord of Ash"),
            page.books.map { it.title }
        )
    }

    @Test
    fun `book shape rule discriminates on the href shape alone`() {
        assertTrue(BookShape.isChapterShapedHref("https://chikari.moe/novels/shadow-slave/214"))
        assertTrue(BookShape.isChapterShapedHref("/novels/shadow-slave/chapter-3"))
        assertFalse(BookShape.isChapterShapedHref("https://chikari.moe/novels/shadow-slave"))
        assertFalse(BookShape.isChapterShapedHref("https://chikari.moe/novels/shadow-slave/"))
        assertTrue(
            BookShape.matchesPattern(
                "https://chikari.moe/novels/shadow-slave/?x=1#y",
                "/novels/[^/]+$"
            )
        )
        assertFalse(
            BookShape.matchesPattern(
                "https://chikari.moe/novels/shadow-slave/214",
                "/novels/[^/]+$"
            )
        )
    }

    // ── Fix 4: Live derivation ──────────────────────────────

    @Test
    fun `live derivation derives a books-only listing titled from cover alts`() {
        val doc = Jsoup.parse(
            ChikariSite.RENDERED_HOME_WITH_COMMENTS, ChikariSite.BASE
        )
        val analysis = LinkInference.analyseListingPage(
            doc, ChikariSite.BASE, null, booksOnly = true
        )
        assertNotNull(analysis)
        val found = analysis!!
        assertEquals("img@alt", found.titleSpec)
        // Run the derived shape the way LiveSource.listingFrom
        // does — through an ephemeral declarative config.
        val ephemeral = DeclarativeConfig(
            id = "live.chikari-moe",
            name = "Chikari",
            baseUrl = ChikariSite.BASE,
            popular = ListSpec(
                url = ChikariSite.BASE,
                results = found.resultsSelector,
                title = found.titleSpec,
                link = found.linkSpec,
                cover = found.coverSpec
            ),
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(list = "a", name = "@text", link = "@href")
            ),
            chapter = ChapterSpec(content = "article")
        )
        val page = DeclarativeSource(
            ephemeral, chikariContext(ChikariSite.RENDERED_HOME_WITH_COMMENTS)
        ).popular(1)
        assertEquals(
            listOf("Shadow Slave", "Lord of Ash", "Dim Harbour"),
            page.books.map { it.title }
        )
    }

    private class MapStore : HostStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun `stale v1 shape entries are never served after the version bump`() {
        assertEquals(
            "shape.v2.search.chikari.moe",
            LiveShapeCache.searchKey("chikari.moe")
        )
        val store = MapStore()
        // A junk shape cached by the pre-fix logic, under the
        // OLD key — the v2 readers never look there.
        store.put("shape.search.chikari.moe", "https://chikari.moe/junk?q={query}")
        assertEquals(
            null, store.get(LiveShapeCache.searchKey("chikari.moe"))
        )
        // Eviction (Repair §6.3 step 1) clears the v2 host keys.
        store.put(LiveShapeCache.searchKey("chikari.moe"), "t")
        store.put(LiveShapeCache.chapterKey("chikari.moe"), "c")
        store.put(LiveShapeCache.chapterKey("chikari.moe") + ".fp", "f")
        LiveShapeCache.evictHostShapes(store, "chikari.moe")
        assertEquals(null, store.get(LiveShapeCache.searchKey("chikari.moe")))
        assertEquals(null, store.get(LiveShapeCache.chapterKey("chikari.moe")))
    }

    // ── Fix 5: rebuildable truth table ──────────────────────

    @Test
    fun `rebuildable truth table`() {
        fun classify(
            packageId: String,
            kind: app.xreader.data.db.ExtensionKind,
            count: Int
        ) = SourceRebuildability.classify(packageId, kind, count)

        assertEquals(
            RebuildKind.LINK,
            classify("link.foo", app.xreader.data.db.ExtensionKind.DECLARATIVE, 1)
        )
        assertEquals(
            RebuildKind.LIVE,
            classify("live.chikari.moe", app.xreader.data.db.ExtensionKind.LIVE, -1)
        )
        // A converted Live row: live.* packageId, DECLARATIVE
        // kind, one config — editable like any single row.
        assertEquals(
            RebuildKind.REPO_DECLARATIVE_SINGLE,
            classify("live.chikari.moe", app.xreader.data.db.ExtensionKind.DECLARATIVE, 1)
        )
        assertEquals(
            RebuildKind.REPO_DECLARATIVE_SINGLE,
            classify("repo.pkg", app.xreader.data.db.ExtensionKind.DECLARATIVE, 1)
        )
        assertEquals(
            RebuildKind.REPO_DECLARATIVE_MULTI,
            classify("repo.pkg", app.xreader.data.db.ExtensionKind.DECLARATIVE, 3)
        )
        assertEquals(
            RebuildKind.REPO_CODE,
            classify("repo.pkg", app.xreader.data.db.ExtensionKind.DEX, -1)
        )
        assertEquals(
            RebuildKind.REPO_CODE,
            classify("repo.pkg", app.xreader.data.db.ExtensionKind.JS, -1)
        )
        assertTrue(SourceRebuildability.isRebuildable(RebuildKind.LINK))
        assertTrue(SourceRebuildability.isRebuildable(RebuildKind.LIVE))
        assertTrue(
            SourceRebuildability.isRebuildable(RebuildKind.REPO_DECLARATIVE_SINGLE)
        )
        assertFalse(
            SourceRebuildability.isRebuildable(RebuildKind.REPO_DECLARATIVE_MULTI)
        )
        assertFalse(SourceRebuildability.isRebuildable(RebuildKind.REPO_CODE))
        // Parser rules: editable for link + single-config repo;
        // read-only while LIVE (editable after conversion).
        assertTrue(SourceRebuildability.hasEditableRules(RebuildKind.LINK))
        assertTrue(
            SourceRebuildability.hasEditableRules(RebuildKind.REPO_DECLARATIVE_SINGLE)
        )
        assertFalse(SourceRebuildability.hasEditableRules(RebuildKind.LIVE))
        assertFalse(SourceRebuildability.hasEditableRules(RebuildKind.REPO_CODE))
    }

    // ── §6.5b: honest search state ──────────────────────────

    @Test
    fun `no-search-by-design does not drag the health verdict`() {
        fun core(cap: SourceCapability, ok: Boolean, na: Boolean = false) =
            CapabilityResult(
                capability = cap, ok = ok,
                detail = if (na) ContractVerdict.SEARCH_NOT_SET_UP_LINE else "",
                notApplicable = na
            )
        val results = listOf(
            core(SourceCapability.LIST, ok = true),
            core(SourceCapability.SEARCH, ok = true, na = true),
            core(SourceCapability.DETAILS, ok = true),
            core(SourceCapability.CHAPTERS, ok = true),
            core(SourceCapability.TEXT, ok = true)
        )
        val verdict = ContractVerdict(
            state = ContractVerdict.judge(results, 1L),
            results = results
        )
        assertEquals(SourceHealthState.WORKING, verdict.state)
        assertTrue(verdict.failedCore().isEmpty())
        // Even recorded defensively (ok = false + notApplicable)
        // the line cannot fail the source.
        val defensive = results.map {
            if (it.capability == SourceCapability.SEARCH) {
                it.copy(ok = false)
            } else {
                it
            }
        }
        assertEquals(
            SourceHealthState.WORKING,
            ContractVerdict.judge(defensive, 1L)
        )
    }
}
