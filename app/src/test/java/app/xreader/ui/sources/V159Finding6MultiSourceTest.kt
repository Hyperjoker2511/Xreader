package app.xreader.ui.sources

import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.KeiyoushiIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.9 (Finding 6): the multi-source package fix — labelled
 * instances, a browsing default, and a remembered pick.
 *
 * The fixture is the owner's own MangaFire case, verbatim
 * from the live Keiyoushi index (V158-PROOF-FINDING-6 §2):
 * one package, seven instances of ONE site in seven
 * languages — the rows the Details page used to render as
 * seven raw Mihon ids ("Open 6084907896154116083", …).
 */
class V159Finding6MultiSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mangaFireIds = listOf(
        "6084907896154116083",
        "8098539940032331958",
        "3849324770075062403",
        "6228021941803932286",
        "524110931382140732",
        "758400875161895515",
        "7842137233968841729"
    )

    private val mangaFireStubs: Map<String, KeiyoushiIndex.SourceStub> =
        listOf(
            KeiyoushiIndex.SourceStub(
                id = "6084907896154116083", name = "MangaFire",
                lang = "en", homeUrl = "https://mangafire.to"
            ),
            KeiyoushiIndex.SourceStub(
                id = "8098539940032331958", name = "MangaFire",
                lang = "es", homeUrl = "https://mangafire.to"
            ),
            KeiyoushiIndex.SourceStub(
                id = "3849324770075062403", name = "MangaFire",
                lang = "es-419", homeUrl = "https://mangafire.to"
            ),
            KeiyoushiIndex.SourceStub(
                id = "6228021941803932286", name = "MangaFire",
                lang = "fr", homeUrl = "https://mangafire.to"
            ),
            KeiyoushiIndex.SourceStub(
                id = "524110931382140732", name = "MangaFire",
                lang = "ja", homeUrl = "https://mangafire.to"
            ),
            KeiyoushiIndex.SourceStub(
                id = "758400875161895515", name = "MangaFire",
                lang = "pt", homeUrl = "https://mangafire.to"
            ),
            KeiyoushiIndex.SourceStub(
                id = "7842137233968841729", name = "MangaFire",
                lang = "pt-BR", homeUrl = "https://mangafire.to"
            )
        ).associateBy { it.id }

    private fun mangaFireInstances() = InstanceLabels.join(
        declaredIds = mangaFireIds,
        stubs = mangaFireStubs,
        handles = emptyList(),
        configs = emptyList()
    )

    // ── (a) the label join ──────────────────────────────────

    @Test
    fun `a - MangaFire fixture labels read name plus language`() {
        val instances = mangaFireInstances()
        assertEquals(
            listOf(
                "MangaFire (EN)",
                "MangaFire (ES)",
                "MangaFire (ES-419)",
                "MangaFire (FR)",
                "MangaFire (JA)",
                "MangaFire (PT)",
                "MangaFire (PT-BR)"
            ),
            instances.map { it.label }
        )
        assertTrue(instances.none { it.usedFallbackLabel })
    }

    @Test
    fun `a - one shared host is named once, never per row`() {
        val instances = mangaFireInstances()
        // No per-instance host line when every instance shares
        // the host; the shared host is named exactly once.
        assertTrue(instances.all { it.hostLine == null })
        assertEquals("mangafire.to", InstanceLabels.sharedHostOf(instances))
    }

    @Test
    fun `a - differing hosts ride as quiet per-instance lines`() {
        val instances = InstanceLabels.join(
            declaredIds = listOf("101", "202"),
            stubs = mapOf(
                "101" to KeiyoushiIndex.SourceStub(
                    id = "101", name = "Alpha", lang = "en",
                    homeUrl = "https://alpha.example"
                ),
                "202" to KeiyoushiIndex.SourceStub(
                    id = "202", name = "Beta", lang = "en",
                    homeUrl = "https://beta.example"
                )
            ),
            handles = emptyList(),
            configs = emptyList()
        )
        assertEquals(
            listOf("Alpha (EN)", "Beta (EN)"),
            instances.map { it.label }
        )
        assertEquals(
            listOf("alpha.example", "beta.example"),
            instances.map { it.hostLine }
        )
        assertNull(InstanceLabels.sharedHostOf(instances))
    }

    @Test
    fun `a - live handles and configs label through the same seam`() {
        // No stubs on device (catalogue never fetched): the
        // live handle's name/baseUrl carries the label.
        val fromHandles = InstanceLabels.join(
            declaredIds = listOf("6084907896154116083"),
            stubs = emptyMap(),
            handles = listOf(
                InstanceSeed(
                    sourceId = "6084907896154116083",
                    name = "MangaFire", lang = "en",
                    homeUrl = "https://mangafire.to"
                )
            ),
            configs = emptyList()
        )
        assertEquals("MangaFire (EN)", fromHandles.single().label)

        // A declarative multi-config row: the configs' own
        // display names label their instances.
        val fromConfigs = InstanceLabels.join(
            declaredIds = listOf("brightnovel", "brightnovel-manga"),
            stubs = emptyMap(),
            handles = emptyList(),
            configs = listOf(
                InstanceSeed(
                    sourceId = "brightnovel", name = "BrightNovel",
                    lang = "en", homeUrl = "https://brightnovel.example"
                ),
                InstanceSeed(
                    sourceId = "brightnovel-manga",
                    name = "BrightNovel Manga", lang = "en",
                    homeUrl = "https://brightnovel.example"
                )
            )
        )
        assertEquals(
            listOf("BrightNovel (EN)", "BrightNovel Manga (EN)"),
            fromConfigs.map { it.label }
        )
    }

    // ── (b) the digits-only guard ───────────────────────────

    @Test
    fun `b - no producible label for a stubbed row is a raw id`() {
        val rowSets = listOf(
            mangaFireInstances(),
            InstanceLabels.join(
                declaredIds = mangaFireIds,
                stubs = emptyMap(),
                handles = mangaFireIds.map { id ->
                    InstanceSeed(
                        sourceId = id, name = "MangaFire",
                        lang = mangaFireStubs[id]!!.lang,
                        homeUrl = "https://mangafire.to"
                    )
                },
                configs = emptyList()
            )
        )
        for (instances in rowSets) {
            for (instance in instances) {
                assertFalse(
                    "label must never be all digits: ${instance.label}",
                    instance.label.all { it.isDigit() }
                )
                assertFalse(
                    "label must never be the raw-id button shape",
                    instance.label == "Open ${instance.sourceId}"
                )
                assertFalse(
                    "label must never contain the raw id",
                    instance.label.contains(instance.sourceId)
                )
                assertFalse(instance.usedFallbackLabel)
            }
        }
    }

    // ── (c) the default ─────────────────────────────────────

    @Test
    fun `c - primary language order picks English for the fixture`() {
        assertEquals(
            "6084907896154116083",
            MultiSourceDefault.resolve(mangaFireInstances(), null)
        )
    }

    @Test
    fun `c - a remembered pick overrides the primary language`() {
        assertEquals(
            "524110931382140732",
            MultiSourceDefault.resolve(
                mangaFireInstances(),
                rememberedPick = "524110931382140732"
            )
        )
    }

    @Test
    fun `c - a stale remembered pick falls back to primary language`() {
        assertEquals(
            "6084907896154116083",
            MultiSourceDefault.resolve(
                mangaFireInstances(),
                rememberedPick = "9999999999999999999"
            )
        )
    }

    @Test
    fun `c - all beats en, and first declared wins with neither`() {
        val allAndEn = InstanceLabels.join(
            declaredIds = listOf("1", "2"),
            stubs = mapOf(
                "1" to KeiyoushiIndex.SourceStub(
                    id = "1", name = "Multi", lang = "en",
                    homeUrl = "https://multi.example"
                ),
                "2" to KeiyoushiIndex.SourceStub(
                    id = "2", name = "Multi", lang = "all",
                    homeUrl = "https://multi.example"
                )
            ),
            handles = emptyList(),
            configs = emptyList()
        )
        assertEquals("2", MultiSourceDefault.resolve(allAndEn, null))

        val noEnNoAll = InstanceLabels.join(
            declaredIds = listOf("6228021941803932286", "524110931382140732"),
            stubs = mangaFireStubs,
            handles = emptyList(),
            configs = emptyList()
        )
        // fr declared before ja; neither en nor all present.
        assertEquals(
            "6228021941803932286",
            MultiSourceDefault.resolve(noEnNoAll, null)
        )
        assertNull(MultiSourceDefault.resolve(emptyList(), null))
    }

    // ── (d) the routing ─────────────────────────────────────

    @Test
    fun `d - a multi-source row tap opens the default catalogue, not Details`() {
        val decision = SourcesRowRouting.decide(
            sourceIds = mangaFireIds,
            defaultSourceId = "6084907896154116083"
        )
        assertEquals(
            SourcesRowRouting.Destination.CATALOGUE, decision.destination
        )
        assertEquals("6084907896154116083", decision.sourceId)
    }

    @Test
    fun `d - single-source behaviour is unchanged`() {
        val decision = SourcesRowRouting.decide(
            sourceIds = listOf("asura"),
            defaultSourceId = null
        )
        assertEquals(
            SourcesRowRouting.Destination.CATALOGUE, decision.destination
        )
        assertEquals("asura", decision.sourceId)
    }

    @Test
    fun `d - no resolvable default keeps the Details route`() {
        val unresolved = SourcesRowRouting.decide(
            sourceIds = mangaFireIds, defaultSourceId = null
        )
        assertEquals(
            SourcesRowRouting.Destination.DETAILS, unresolved.destination
        )
        assertNull(unresolved.sourceId)
        // A default naming an instance the row no longer has
        // is never trusted either.
        val stale = SourcesRowRouting.decide(
            sourceIds = mangaFireIds, defaultSourceId = "42"
        )
        assertEquals(
            SourcesRowRouting.Destination.DETAILS, stale.destination
        )
        assertEquals(
            SourcesRowRouting.Destination.DETAILS,
            SourcesRowRouting.decide(emptyList(), null).destination
        )
    }

    // ── (e) the remembered pick through the real store ──────

    @Test
    fun `e - a pick round-trips through the extension store and moves the default`() {
        val dir = tmp.newFolder()
        val mangaFirePkg = "eu.kanade.tachiyomi.extension.all.mangafire"
        val mangaBallPkg = "eu.kanade.tachiyomi.extension.all.mangaball"

        val writer = ExtensionStore(dir)
        assertNull(writer.instancePick(mangaFirePkg))
        writer.writeInstancePick(mangaFirePkg, "524110931382140732")
        writer.writeInstancePick(mangaBallPkg, "424242")

        // A FRESH store over the same directory (a new process)
        // reads the same picks — the value is durable, and the
        // packages' keys are independent.
        val reader = ExtensionStore(dir)
        assertEquals(
            "524110931382140732", reader.instancePick(mangaFirePkg)
        )
        assertEquals("424242", reader.instancePick(mangaBallPkg))
        assertNull(reader.instancePick("eu.kanade.tachiyomi.extension.en.asura"))

        // And the default follows the re-read pick: JA, not EN.
        assertEquals(
            "524110931382140732",
            MultiSourceDefault.resolve(
                mangaFireInstances(),
                rememberedPick = reader.instancePick(mangaFirePkg)
            )
        )
    }
}
