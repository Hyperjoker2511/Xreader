package app.xreader.data.extension.player

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.xreader.core.error.AppResult
import app.xreader.data.extension.ContentRating
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import eu.kanade.tachiyomi.source.ConfigurableSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * K6: the fixture-extension instrumented test — the player's
 * device proof that no JVM test can give. It loads two REAL
 * signed extension APKs (canned, offline; built by
 * tools/keiyoushi-fixture/build-fixtures.sh into this test's
 * assets) through the player's own reader + loader, and runs
 * one source end-to-end through the K4 adapter:
 * catalogue → details → chapters (TRUE numbers) → pages
 * (with the source's own image-url resolution and the K6
 * Referer stamp) → page bytes through the source's image
 * path. Fixture B proves the SourceFactory shape.
 *
 * What this deliberately does NOT prove: the manager's full
 * install path. The fixtures are signed with a local test
 * key, never Keiyoushi's, so the manager must refuse them —
 * the trust test below pins exactly that refusal. The
 * manager's flow itself is JVM-proven (K3/K6 manager tests);
 * its live-APK half is the device smoke checklist
 * (build-logs/K6-SMOKE-CHECKLIST.md) against the real repo.
 */
class KeiyoushiFixtureLoadTest {

    private lateinit var appContext: Context
    private lateinit var reader: PackageManagerKeiyoushiApkReader
    private lateinit var loader: AndroidKeiyoushiExtensionLoader

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        appContext = instrumentation.targetContext
        reader = PackageManagerKeiyoushiApkReader(appContext)
        loader = AndroidKeiyoushiExtensionLoader(appContext)
    }

    private fun assetFile(name: String): File {
        val testContext =
            InstrumentationRegistry.getInstrumentation().context
        val out = File(appContext.cacheDir, name)
        testContext.assets.open(name).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    @Test
    fun readerReadsTheFixtureAndTrustRefusesItsKey() {
        val file = assetFile("fixture-panels.apk")
        val metadata = reader.read(file)
        assertNotNull("the fixture must read as an extension", metadata)
        metadata!!
        assertEquals(
            "eu.kanade.tachiyomi.extension.en.fixturepanels",
            metadata.packageName
        )
        assertEquals(
            "fixture.panels.FixturePanelsSource", metadata.entryClass
        )
        assertEquals(1.6f, metadata.extensionLib, 0.001f)
        assertTrue(metadata.certSha256s.isNotEmpty())
        // The fixture's whole point: signed, but NOT by
        // Keiyoushi — the certificate check must refuse it
        // against the index key, so the manager's install
        // path can never be exercised by fixtures.
        val verdict = KeiyoushiTrust.verifyCertificate(
            metadata.certSha256s, KeiyoushiTrust.EXPECTED_SIGNING_KEY
        )
        assertTrue(
            verdict is KeiyoushiTrust.Verdict.Refuse
        )
        assertTrue(
            KeiyoushiTrust.verifyLibVersion(
                KeiyoushiTrust.libVersionOf(
                    metadata.extensionLib, metadata.versionName
                )
            ) is KeiyoushiTrust.Verdict.Accept
        )
    }

    @Test
    fun singleSourceLoadsAndRunsThroughTheAdapter() = runBlocking {
        val file = assetFile("fixture-panels.apk")
        val metadata = reader.read(file)!!
        val sources = loader.load(file, metadata)
        assertEquals(1, sources.size)
        val source = sources.single()
        assertEquals(901001L, source.id)
        assertEquals("Fixture Panels", source.name)
        assertTrue(source is ConfigurableSource)

        val adapter = MihonSourceAdapter(
            packageName = metadata.packageName,
            namespacedId = "keiyoushi:${metadata.packageName}:901001",
            mihon = source,
            contentRating = ContentRating.SAFE
        )
        val popular = adapter.popular(1)
        assertEquals(
            listOf("Fixture Alpha", "Fixture Beta"),
            popular.books.map { it.title }
        )
        val search = adapter.search("beta", 1)
        assertEquals(
            listOf("Fixture Beta"), search.books.map { it.title }
        )
        val details = adapter.details(
            SourceBook(id = "/manga/alpha", title = "Fixture Alpha")
        )
        assertEquals("Fixture Author", details.author)
        val chapters = adapter.chapters(
            SourceBook(id = "/manga/alpha", title = "Fixture Alpha")
        )
        assertEquals(
            listOf(1f, 2f, 3f), chapters.map { it.number }
        )
        adapter.chapterContent(
            SourceChapter(id = "/ch/1", name = "Chapter 1")
        )
        val pages = adapter.pagesOf("/ch/1")
        assertNotNull(pages)
        assertEquals(
            listOf(
                "https://cdn.fixture/1.png",
                "https://cdn.fixture/resolved/p2",
                "https://cdn.fixture/3.png"
            ),
            pages!!.urls
        )
        // The K6 Referer thread, on the real load path.
        assertEquals("https://fixture.invalid/", pages.referer)
        val bytes = adapter.pageBytes("https://cdn.fixture/1.png")
        assertTrue(bytes is AppResult.Ok)
        assertTrue((bytes as AppResult.Ok).value.isNotEmpty())
    }

    @Test
    fun factoryLoadsBothSources() {
        val file = assetFile("fixture-factory.apk")
        val metadata = reader.read(file)!!
        val sources = loader.load(file, metadata)
        assertEquals(
            listOf(901002L, 901003L), sources.map { it.id }
        )
        assertEquals(
            listOf("Fixture Factory A", "Fixture Factory B"),
            sources.map { it.name }
        )
    }

    @Test
    fun preferenceHostBuildsTheFixturesScreen() {
        val file = assetFile("fixture-panels.apk")
        val metadata = reader.read(file)!!
        val source = loader.load(file, metadata).single()
        // JUnit's fail() returns Unit (not Nothing), so it cannot
        // smart-cast; assert the type, then cast explicitly.
        assertTrue(
            "the fixture source must be configurable",
            source is ConfigurableSource
        )
        val host = SourcePreferenceHost(
            appContext, metadata.packageName,
            source as ConfigurableSource
        )
        val rows = host.build()
        val toggle = rows.firstOrNull { it.title == "Fixture toggle" }
        assertNotNull(toggle)
        assertEquals(SourcePrefKind.SWITCH, toggle!!.kind)
        assertTrue(toggle.checked)
        val choice = rows.firstOrNull { it.title == "Fixture choice" }
        assertNotNull(choice)
        assertEquals(SourcePrefKind.CHOICE, choice!!.kind)
        assertEquals(listOf("First", "Second"), choice.options)
        // An edit writes through the live preference object.
        val after = host.setChecked(toggle.id, false)
        assertEquals(
            false,
            after.first { it.title == "Fixture toggle" }.checked
        )
    }
}
