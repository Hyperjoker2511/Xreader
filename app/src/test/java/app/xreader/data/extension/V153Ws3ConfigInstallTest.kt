package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.3 (finding #3 programme, WS-3): the config & install
 * obligations of items 4, 9-declarations, 11, 12, 13-config,
 * 14 and 17-declarations — the bundled configs declare the
 * shapes the findings evidenced, and a bundled row carried
 * over from v1.5.2 refreshes onto them (rules AND
 * permissions) through [RepoManager.installBundledDefaults].
 *
 * Declaration tests read the configs exactly as
 * [BundledDefaults.configs] instantiates them — the same
 * objects the install path fingerprints — so a declaration
 * that drifts from what shipped fails here, not on a phone.
 */
class V153Ws3ConfigInstallTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** NoBadNovel's fingerprint as written by v1.5.2. */
    private val noBadNovelV152 =
        "f066314feac366d8e09b75a035a6980d5a75f6eafabfb1061379ad8240589edb"

    private fun bundled(id: String): DeclarativeConfig =
        BundledDefaults.configs().first { it.id == id }

    private fun resourceText(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    // ── item 11: NoBadNovel listing records ─────────────

    @Test
    fun `noBadNovel listing records carry cdn covers`() {
        // The recorded listing's first two cards verbatim
        // (fixtures-capture/nobadnovel.com/listing-p1.html):
        // the widened card-root spec must reach each card's
        // <img>, whose src serves from the cdn host — the
        // shipped anchor-only spec could never see it.
        val html = resourceText("nobadnovel/listing.html")
        val transport = FakeTransport { FakeTransport.ok(html) }
        val source = DeclarativeSource(
            bundled("bundled-nobadnovel"),
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(
                    domains = listOf(
                        "www.nobadnovel.com", "cdn.nobadnovel.com"
                    )
                )
            )
        )
        val page = source.popular(1)
        assertEquals(2, page.books.size)
        assertEquals(
            "Longevity: I Am an Angel Investor in the Cultivation World",
            page.books[0].title
        )
        assertEquals(
            "https://www.nobadnovel.com/series/longevity-i-am-an-angel-investor-in-the-cultivation-world",
            page.books[0].id
        )
        assertEquals(
            "https://cdn.nobadnovel.com/2026/9/29/p2o415719447041e36e4311532949828b02~tplv-resize:225:300_1k63i5nfk.png",
            page.books[0].coverUrl
        )
        assertEquals(
            "Ten Thousand Years of Cultivation Per Second, How Will You Respond, Sir?",
            page.books[1].title
        )
        assertEquals(
            "https://www.nobadnovel.com/series/ten-thousand-years-of-cultivation-per-second-how-will-you-respond-sir",
            page.books[1].id
        )
        assertEquals(
            "https://cdn.nobadnovel.com/2026/9/23/p2o688a7459a14b3cbecf2787ccfe3e1d41~tplv-resize:225:300_1k5jcmass.png",
            page.books[1].coverUrl
        )
        assertTrue(
            page.books.all {
                it.coverUrl
                    ?.startsWith("https://cdn.nobadnovel.com/") == true
            }
        )
    }

    // ── item 12: bundled refresh rewrites permissions ───

    private fun managerWith(
        extDao: FakeInstalledExtensionDao,
        store: ExtensionStore
    ): RepoManager {
        val repoDao = FakeExtensionRepoDao()
        val sessionStore = SessionStore(testVault())
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(404, ""))
        }
        val contextFactory = HostContextFactory(store, sessionStore, transport)
        val service = ExtensionService(
            extDao, store, contextFactory, FakeDexLoader()
        ) { FakeJsRuntime() }
        return RepoManager(
            repoDao, extDao, store, transport, service, sessionStore
        )
    }

    @Test
    fun `bundled refresh rewrites permissions so the new cdn domain lands`() {
        runBlocking {
            val extDao = FakeInstalledExtensionDao()
            val store = ExtensionStore(tmp.newFolder())
            val manager = managerWith(extDao, store)
            // First install: every bundled row lands at the
            // current fingerprint and permissions.
            manager.installBundledDefaults()
            // Roll the NoBadNovel row back to exactly what
            // v1.5.2 left behind: its v1.5.2 fingerprint and
            // a permissions blob naming only the base host
            // (v1.5.2's config declared no extra domains).
            val row = extDao.getByPackageId("bundled.bundled-nobadnovel")!!
            extDao.update(
                row.copy(
                    sha256 = noBadNovelV152,
                    permissionsJson =
                        """{"domains":["www.nobadnovel.com"]}"""
                )
            )
            store.writeText(
                store.definitionFile("bundled.bundled-nobadnovel"),
                "LEGACY-V152"
            )
            // v1.5.4 (item 9): a v1.5.2 install has no epoch
            // sidecar — remove the one the first install just
            // stamped, or the row reads as epoch-current and
            // the rollback isn't a rollback. The refresh below
            // now fires on the epoch (spec §0.3), not on the
            // v1.5.2 fingerprint above being in a shipped set.
            java.io.File(
                store.packageDir("bundled.bundled-nobadnovel"),
                "bundled-epoch"
            ).delete()
            manager.installBundledDefaults()
            val refreshed =
                extDao.getByPackageId("bundled.bundled-nobadnovel")!!
            assertEquals(
                "the row must carry the current fingerprint",
                DeclarativeFingerprints.of(
                    listOf(bundled("bundled-nobadnovel"))
                ),
                refreshed.sha256
            )
            val permissions =
                ExtensionPermissions.parse(refreshed.permissionsJson)
            assertTrue(
                "cdn.nobadnovel.com must be permitted after the refresh",
                permissions.allows("cdn.nobadnovel.com")
            )
            assertTrue(
                "the base host must stay permitted",
                permissions.allows("www.nobadnovel.com")
            )
        }
    }

    // ── item 12 / §5.12: the shipped-fingerprint guard ──

    @Test
    fun `shipped fingerprints include the v152 values of every changed config`() {
        // The v1.5.2 fingerprint of every config this build
        // changes (derivation table:
        // build-logs/V153-WS3-FINGERPRINT-DERIVATION.txt).
        // Without its entry a v1.5.2 install never enters the
        // refresh branch and silently keeps the old rules.
        val expected = listOf(
            "bundled-brightnovel" to
                "ea67e4eb62d721926523314812b0ac2b34abbbe14fc651246b3a69943ef5979d",
            "bundled-chikari" to
                "0d521f50d6b3f023851f53b089fed015f09be737104bb95056de2ff695062423",
            "bundled-fenrirealm" to
                "9ffe6985ecaeb9f1c18d99ed7b73f8b5c089318e9e64d624456e6b091dbb5f15",
            "bundled-hiraethtranslation" to
                "d69d7b632db73e76a3a3dd4b1654aa1d093dfcdc3c87ec16ddda04efa0348f45",
            "bundled-lnt" to
                "dbb3374fb3dcdbec56cee6e94f0580de90e95c42410ebb27a12f24a4051f9ee7",
            "bundled-nobadnovel" to
                "f066314feac366d8e09b75a035a6980d5a75f6eafabfb1061379ad8240589edb",
            "bundled-novelarchive" to
                "87e808452e5fa54c538a78945cab422e21d95f2e0d66794608abdd4bc9d3a54c",
            "bundled-noveli" to
                "2c2444695d3bda6c0de7e92db9f479547d43a4ca6bf1960b0bb108fd50fd9a44",
            "bundled-novelfire" to
                "9826c647c2043c6a5608886a56be6ee3a5f16b7584df9cf309cb3c8f97832b7d",
            "bundled-novelphoenix" to
                "194cb08f16c39ca61742d1e90fc1d5f1038e13a8d1cba5c9f07d49d5ee6da9ec",
            "bundled-ranovel" to
                "90fe8288be0ad16e86a3149f274fcb5033af0ad0e6eb4fcda96cd0886856abfa",
            "bundled-royalroad" to
                "5dfec978d2e758222fdc770b9977ed7aa91c720d4210db9ce7a95d38377f6225",
            "bundled-scrollerspub" to
                "69e97fc059b51d4650e14844b14104bc75f386a692a633f3c5bedb43928c482f",
            "bundled-sonicmtl" to
                "dd241d00901c46f9fabc9bd8fba8c83ed31d9c41ebf73f2426809c5f4d9d1bb9",
            "bundled-wetriedtls" to
                "8936a2dfe52edd7f00c7c3f9a5254e34d02d5f866146fa4e60ccfe1fafee0946",
            "bundled-wntl" to
                "52cf65ee25704ddcfe27ebe399b452649d661e18f08b7fb0b7f241b5c403e530",
            "bundled-wordrain69" to
                "34c35e698333a4f623caf0e49fe204783b7b4bcbac742da938e259e12bf5b5da"
        )
        for ((id, fingerprint) in expected) {
            assertTrue(
                "$id must list its v1.5.2 fingerprint $fingerprint",
                BundledDefaults.SHIPPED_FINGERPRINTS[id]
                    ?.contains(fingerprint) == true
            )
        }
    }

    // ── item 4: BrightNovel's Inertia wire ──────────────

    @Test
    fun `brightNovel declares the inertia wire shapes`() {
        val config = bundled("bundled-brightnovel")
        assertTrue(config.needsRendering)
        assertEquals("div#app[data-page]", config.renderWaitSelector)
        val popular = config.popular!!
        assertEquals("https://brightnovels.com/series", popular.url)
        assertEquals("props.seriesList.data", popular.jsonPath)
        assertEquals("title", popular.title)
        assertEquals("slug", popular.link)
        assertEquals("cover.url", popular.cover)
        assertEquals("/series/{value}", popular.jsonLinkTemplate)
        val search = config.search!!
        assertEquals(
            "https://brightnovels.com/api/search?query={query}",
            search.url
        )
        assertEquals("data.series", search.jsonPath)
        assertEquals("title", search.title)
        assertEquals("slug", search.link)
        assertEquals("cover.url", search.cover)
        assertEquals("/series/{value}", search.jsonLinkTemplate)
        val chapters = config.details.chapters
        assertEquals("name", chapters.jsonNameField)
        assertEquals("slug", chapters.jsonLinkField)
        assertEquals(
            "https://brightnovels.com/series/{bookSlug}/{value}",
            chapters.jsonLinkTemplate
        )
        assertEquals("updated_at", chapters.jsonDateField)
        assertTrue(chapters.reverse)
        val pagination = chapters.pagination!!
        assertEquals("chapters", pagination.jsonArrayPath)
        assertEquals(1000, pagination.pageSize)
        assertEquals(
            "https://brightnovels.com/series/{bookSlug}/chapters/free?loaded=0&sort_order=desc",
            pagination.urlTemplate
        )
        assertEquals("props.chapter.content", config.chapter.content)
    }

    // ── item 9: class-C details JSON covers ─────────────

    @Test
    fun `class C sources declare their json details covers`() {
        val expected: Map<String, Pair<String, String?>> = mapOf(
            "bundled-fenrirealm" to
                ("cover" to "https://fenrirealm.com/{value}"),
            "bundled-chikari" to ("cover_url" to null),
            "bundled-scrollerspub" to ("cover_url" to null),
            "bundled-noveli" to ("cover_image" to null),
            "bundled-novelarchive" to ("cover_url" to null),
            "bundled-wetriedtls" to ("thumbnail" to null),
            "bundled-wntl" to ("cover" to null)
        )
        for ((id, cover) in expected) {
            val details = bundled(id).details
            assertEquals("$id jsonCover", cover.first, details.jsonCover)
            assertEquals(
                "$id jsonCoverTemplate",
                cover.second, details.jsonCoverTemplate
            )
        }
    }

    // ── item 13: NovelFire family's lazy chain ──────────

    @Test
    fun `novelFire family declares the lazy chain and details cover`() {
        for (id in listOf("bundled-novelfire", "bundled-novelphoenix")) {
            val config = bundled(id)
            assertEquals(
                "$id popular cover",
                "img@data-src || img@src", config.popular?.cover
            )
            assertEquals(
                "$id search cover",
                "img@data-src || img@src", config.search?.cover
            )
            assertEquals(
                "$id details cover",
                "figure.cover img@src", config.details.cover
            )
        }
    }

    // ── item 17: LNT's deny pattern + details cover ─────

    @Test
    fun `lnt declares the banner deny pattern and details cover`() {
        val config = bundled("bundled-lnt")
        assertEquals(
            listOf("/plugins/lnt-membership/"),
            config.coverDenyPatterns
        )
        assertEquals(
            "div.novel-image img@src", config.details.cover
        )
    }

    // ── items 12/14: domain + card-spec declarations ────

    @Test
    fun `royalRoad permits its cdn cover host`() {
        assertEquals(
            listOf("api.royalroad.com", "www.royalroadcdn.com"),
            bundled("bundled-royalroad").extraDomains
        )
    }

    @Test
    fun `noBadNovel declares the cdn domain and card root spec`() {
        val config = bundled("bundled-nobadnovel")
        assertEquals(
            listOf("cdn.nobadnovel.com"), config.extraDomains
        )
        val popular = config.popular!!
        assertEquals("div.grid > div", popular.results)
        assertEquals("h4 a@text", popular.title)
        assertEquals("h4 a@href", popular.link)
        assertEquals("img@src", popular.cover)
    }
}
