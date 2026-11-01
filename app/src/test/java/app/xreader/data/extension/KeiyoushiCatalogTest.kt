package app.xreader.data.extension

import app.xreader.core.error.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keiyoushi player (K3): the installable catalogue parse —
 * the tachiyomix shape the live repo publishes (verified
 * field-for-field against the real index during K0: root
 * signingKey, resources.apkUrl, string versionCode) plus
 * the classic array shape, and the version-code convention.
 */
class KeiyoushiCatalogTest {

    private val tachiyomixFixture = """
        {
          "name": "Keiyoushi",
          "signingKey": "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
          "extensionList": {"extensions": [
            {
              "name": "Asura Scans",
              "packageName": "eu.kanade.tachiyomi.extension.en.asurascans",
              "contentWarning": "CONTENT_WARNING_SAFE",
              "extensionLib": "1.6",
              "versionCode": "106071",
              "versionName": "1.6.71",
              "resources": {
                "apkUrl": "https://example.test/tachiyomi-en.asurascans-v1.6.71.apk",
                "iconUrl": "https://example.test/icon.png"
              },
              "sources": [
                {"id": "6247824327199706550", "name": "Asura Scans",
                 "language": "en", "homeUrl": "https://asurascans.com"}
              ]
            },
            {
              "name": "No Download",
              "packageName": "eu.kanade.tachiyomi.extension.en.nodownload",
              "contentWarning": "CONTENT_WARNING_NSFW",
              "sources": [
                {"id": "1", "name": "No Download", "language": "en",
                 "homeUrl": "https://nodownload.example"}
              ]
            }
          ]}
        }
    """.trimIndent()

    @Test
    fun `the tachiyomix catalog parses packages and the signing key`() {
        val result = KeiyoushiIndex.parseCatalog(tachiyomixFixture)
        assertTrue(result is AppResult.Ok)
        val catalog = (result as AppResult.Ok).value
        assertEquals(
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            catalog.signingKey
        )
        // The package without an apkUrl is not installable
        // and drops out of the catalogue (its sources still
        // list through parse()).
        assertEquals(1, catalog.packages.size)
        val pkg = catalog.packages.single()
        assertEquals(
            "eu.kanade.tachiyomi.extension.en.asurascans",
            pkg.packageName
        )
        assertEquals("1.6.71", pkg.versionName)
        assertEquals(106071, pkg.versionCode)
        assertEquals("1.6", pkg.extensionLib)
        assertEquals(
            "https://example.test/tachiyomi-en.asurascans-v1.6.71.apk",
            pkg.apkUrl
        )
        assertEquals(ContentRating.SAFE, pkg.rating)
        val source = pkg.sources.single()
        assertEquals("6247824327199706550", source.id)
        assertEquals("Asura Scans", source.name)
        assertEquals("en", source.lang)
        assertEquals("https://asurascans.com", source.homeUrl)
    }

    @Test
    fun `the classic catalog resolves apk files beside the index`() {
        val classic = """
            [
              {"name": "Quiet Shelf",
               "pkg": "eu.kanade.tachiyomi.extension.en.quiet",
               "apk": "tachiyomi-en.quiet.apk", "nsfw": 0,
               "version": "1.6.5",
               "sources": [
                 {"name": "Quiet Shelf", "lang": "en", "id": "61",
                  "baseUrl": "https://quiet.example"}
               ]}
            ]
        """.trimIndent()
        val result = KeiyoushiIndex.parseCatalog(classic)
        assertTrue(result is AppResult.Ok)
        val catalog = (result as AppResult.Ok).value
        // The classic shape publishes no signing key — the
        // player's index-key check refuses installs from it.
        assertEquals(null, catalog.signingKey)
        val pkg = catalog.packages.single()
        assertTrue(pkg.apkUrl.endsWith("/tachiyomi-en.quiet.apk"))
        assertEquals(106005, pkg.versionCode)
        assertEquals("61", pkg.sources.single().id)
    }

    @Test
    fun `a document with no installable packages is a parse failure`() {
        val result = KeiyoushiIndex.parseCatalog("""{"hello": 1}""")
        assertTrue(result is AppResult.Err)
    }

    @Test
    fun `version codes follow the tachiyomi convention`() {
        assertEquals(106071, KeiyoushiIndex.versionCodeOf("1.6.71"))
        assertEquals(106005, KeiyoushiIndex.versionCodeOf("1.6.5"))
        assertEquals(100000, KeiyoushiIndex.versionCodeOf("1.0"))
        assertEquals(0, KeiyoushiIndex.versionCodeOf(""))
        // Gate correction (v1.4.0): this expectation previously
        // read 200301, which no single formula reconciles with
        // the two LIVE-index fixtures pinned in this same suite
        // (1.6.71 → 106071, 1.6.5 → 106005 — both are
        // major×100000 + minor×1000 + patch). Under that formula
        // 2.3.1 is 203001; the old figure was an arithmetic slip
        // in the test, not the convention.
        assertEquals(203001, KeiyoushiIndex.versionCodeOf("2.3.1"))
    }
}
