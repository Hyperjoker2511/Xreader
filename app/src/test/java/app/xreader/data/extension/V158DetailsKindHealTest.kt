package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (B1 / F3 Fix 1): DETAILS-CARRIED KIND. A kindless
 * (UNKNOWN) stored Fenrir-shaped row + a recorded series
 * document with `type = webtoon` → one refresh (the
 * details parse SourceBookSync's applyTier1 sites consume)
 * → the details carry MANGA. The same fixture with
 * `type = novel` → TEXT (the novel fence: Fix 1 reads the
 * value novel payloads already file under). Driven against
 * the SHIPPED [BundledSingles.fenrirRealm] definition and
 * the recorded live response shapes (2026-11-03 captures),
 * never a test-local config copy.
 */
class V158DetailsKindHealTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    private fun source(): DeclarativeSource {
        val config = BundledSingles.fenrirRealm(
            "bundled-fenrirealm", "FenrirRealm"
        )
        val transport = FakeTransport { request ->
            val body = when {
                request.url.endsWith(
                    "/series/the-dragon-of-dreams/chapters"
                ) -> resource("fenrir/chapters-dragon.json")
                request.url.endsWith("/series/the-dragon-of-dreams") ->
                    resource("fenrir/detail-dragon.json")
                request.url.endsWith(
                    "/series/absolute-regression/chapters"
                ) -> resource("fenrir/chapters-absolute-regression.json")
                request.url.endsWith("/series/absolute-regression") ->
                    resource("fenrir/detail-absolute-regression.json")
                else -> null
            }
            if (body != null) {
                FakeTransport.ok(body)
            } else {
                AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf("fenrirealm.com"))
        )
        return DeclarativeSource(config, context)
    }

    @Test
    fun `kindless row heals to MANGA from its own webtoon document`() {
        val kindless = SourceBook(
            id = "https://fenrirealm.com/api/new/v2/series/" +
                "the-dragon-of-dreams",
            title = "The Dragon of Dreams",
            kind = null
        )
        val (details, _) = source().detailsAndChapters(kindless)
        assertEquals(
            "the details document's own type=webtoon must file " +
                "the book MANGA at refresh (B1)",
            BookKind152.MANGA, details.kind
        )
    }

    @Test
    fun `kindless row heals to TEXT from its own novel document`() {
        val kindless = SourceBook(
            id = "https://fenrirealm.com/api/new/v2/series/" +
                "absolute-regression",
            title = "Absolute Regression",
            kind = null
        )
        val (details, _) = source().detailsAndChapters(kindless)
        assertEquals(
            "the novel fence: a novel payload files TEXT",
            BookKind152.TEXT, details.kind
        )
    }

    @Test
    fun `standalone details read carries the kind too`() {
        val kindless = SourceBook(
            id = "https://fenrirealm.com/api/new/v2/series/" +
                "the-dragon-of-dreams",
            title = "The Dragon of Dreams",
            kind = null
        )
        val details = source().details(kindless)
        assertEquals(BookKind152.MANGA, details.kind)
    }
}
