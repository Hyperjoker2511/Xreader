package app.xreader.data.extension

import app.xreader.data.prefs.PerfTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.8.2: a saved app-shell source's OWN fetches carry the
 * wait-for-content hint its config declares. v1.0.8.1 wired the
 * hint into the probe's lane drive only — the add worked, but
 * on a slow connection the catalogue render settled on a
 * chrome-only intermediate page (header/footer clear the shell
 * band) before the book cards arrived, and Browse showed an
 * empty catalogue. The drive had the hint; the source didn't.
 */
class V1082RuntimeHintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun runtime(): RenderRuntime {
        val runtime = RenderRuntime(
            MutableStateFlow(true), MutableStateFlow(PerfTier.FULL)
        )
        runtime.appInForeground = true
        return runtime
    }

    @Test
    fun `a marked source's catalogue fetch carries its declared wait selector`() =
        runBlocking {
            val entry = KnownSites.entryFor(ChikariSite.HOST)!!
            val lane = KnownSites.fullConfig(
                entry, "chikari", "Chikari", ChikariSite.BASE
            )!!
            val hint = lane.renderWaitSelector
            assertTrue(hint != null)
            val renderer = FakeRenderedFetcher(
                ChikariSite.renderedPages(), ChikariSite::renderedFallback
            )
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()),
                testSessionStore(),
                ChikariSite.transport(),
                renderedFetcher = renderer,
                renderRuntime = runtime()
            )
            val context = factory.contextFor(
                extensionRowId = 11L,
                packageId = "link.chikari.moe",
                label = "Chikari",
                permissions = {
                    ExtensionPermissions(domains = listOf(ChikariSite.HOST))
                },
                needsRendering = { true },
                waitForSelector = { hint }
            )
            val source = DeclarativeSource(lane, context)
            val page = source.popular(1)
            assertEquals(3, page.books.size)
            assertTrue(renderer.calls.isNotEmpty())
            // Every render the source made carried the hint.
            assertEquals(
                List(renderer.calls.size) { hint }, renderer.selectors
            )
        }

    @Test
    fun `a source context without a hint renders without one`() =
        runBlocking {
            val renderer = FakeRenderedFetcher(
                ChikariSite.renderedPages(), ChikariSite::renderedFallback
            )
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()),
                testSessionStore(),
                ChikariSite.transport(),
                renderedFetcher = renderer,
                renderRuntime = runtime()
            )
            val context = factory.contextFor(
                extensionRowId = 12L,
                packageId = "link.chikari.moe",
                label = "Chikari",
                permissions = {
                    ExtensionPermissions(domains = listOf(ChikariSite.HOST))
                },
                needsRendering = { true }
            )
            val entry = KnownSites.entryFor(ChikariSite.HOST)!!
            val lane = KnownSites.fullConfig(
                entry, "chikari", "Chikari", ChikariSite.BASE
            )!!
            val page = DeclarativeSource(lane, context).popular(1)
            assertEquals(3, page.books.size)
            assertTrue(renderer.selectors.isNotEmpty())
            assertTrue(renderer.selectors.all { it == null })
        }
}
