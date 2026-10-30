package app.xreader.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repo-format recognition (v1.0.1 hotfix): a foreign repository
 * index must be NAMED (ecosystem + its sites) instead of failing
 * as a generic parse error, Xreader's own shape must never be
 * misread as foreign, and malformed input degrades to Unknown —
 * never an exception.
 */
class RepoFormatTest {

    @Test
    fun `mihon index is recognised and sites dedupe by baseUrl`() {
        val body = """
            [
              {
                "name": "Alpha Pack",
                "pkg": "eu.kanade.tachiyomi.extension.en.alpha",
                "apk": "tachiyomi-en.alpha-v1.apk",
                "version": "1.4.1", "lang": "en",
                "sources": [
                  {"name": "Alpha", "lang": "en", "id": "11",
                    "baseUrl": "https://alpha.example"},
                  {"name": "Beta", "lang": "en", "id": "12",
                    "baseUrl": "https://beta.example"}
                ]
              },
              {
                "name": "Gamma Pack",
                "pkg": "eu.kanade.tachiyomi.extension.es.gamma",
                "apk": "tachiyomi-es.gamma-v1.apk",
                "version": "1.4.0", "lang": "es",
                "sources": [
                  {"name": "Alpha Mirror", "lang": "en", "id": "13",
                    "baseUrl": "https://alpha.example"},
                  {"name": "Gamma", "lang": "es", "id": "14",
                    "baseUrl": "https://gamma.example"}
                ]
              }
            ]
        """.trimIndent()
        val result = detectRepoFormat(body)
        assertTrue(result is RepoFormat.ForeignRepo)
        val foreign = result as RepoFormat.ForeignRepo
        assertEquals("Mihon", foreign.ecosystemName)
        assertEquals(
            "the duplicate https://alpha.example source is listed once",
            listOf(
                "https://alpha.example",
                "https://beta.example",
                "https://gamma.example"
            ),
            foreign.sites.map { it.baseUrl }
        )
        assertEquals("Alpha", foreign.sites.first().name)
        assertEquals("en", foreign.sites.first().lang)
        assertEquals("es", foreign.sites.last().lang)
        assertFalse(
            "an index that lists its sources is not a plugin index",
            foreign.pluginIndex
        )
    }

    @Test
    fun `kototoro plugin index is recognised and named, with no sites`() {
        // The verbatim shape of the live Kototoro repository
        // index (index.min.json, fetched 2026-10-30): ONE
        // package entry pointing at a single plugin.jar, no
        // sources list at all — the sites are code inside the
        // jar. v1.0.8.1 read this as Unknown ("isn't a
        // repository Xreader recognises"); it is a real
        // repository and must be named.
        val body = """
            [
              {
                "name": "Kototoro Parsers",
                "pkg": "org.skepsun.kototoro.parsers",
                "version": "1.0.155",
                "code": 155,
                "apk": "plugin.jar",
                "lang": "all",
                "nsfw": 0
              }
            ]
        """.trimIndent()
        val result = detectRepoFormat(body)
        assertTrue(result is RepoFormat.ForeignRepo)
        val foreign = result as RepoFormat.ForeignRepo
        assertEquals("Kototoro", foreign.ecosystemName)
        assertTrue(foreign.sites.isEmpty())
        assertTrue(
            "a package index with no readable sources is a plugin index",
            foreign.pluginIndex
        )
    }

    @Test
    fun `tachiyomi plugin index without sources is a Mihon plugin index`() {
        val body = """
            [
              {
                "name": "All in One",
                "pkg": "eu.kanade.tachiyomi.extension.all.pack",
                "apk": "tachiyomi-all.pack-v1.apk",
                "version": "1.0", "code": 1, "lang": "all"
              }
            ]
        """.trimIndent()
        val result = detectRepoFormat(body)
        assertTrue(result is RepoFormat.ForeignRepo)
        val foreign = result as RepoFormat.ForeignRepo
        assertEquals("Mihon", foreign.ecosystemName)
        assertTrue(foreign.sites.isEmpty())
        assertTrue(foreign.pluginIndex)
    }

    @Test
    fun `unnamed plugin index is another reader app's plugin index`() {
        val body = """
            [
              {
                "name": "Parsers Bundle",
                "pkg": "com.example.parsers",
                "apk": "plugin.jar",
                "version": "2.1", "code": 7
              }
            ]
        """.trimIndent()
        val result = detectRepoFormat(body)
        assertTrue(result is RepoFormat.ForeignRepo)
        val foreign = result as RepoFormat.ForeignRepo
        assertEquals("another reader app", foreign.ecosystemName)
        assertTrue(foreign.sites.isEmpty())
        assertTrue(foreign.pluginIndex)
    }

    @Test
    fun `lnreader index is recognised from site fields`() {
        val body = """
            [
              {
                "id": "novelcool", "name": "NovelCool",
                "site": "https://novelcool.example",
                "lang": "en", "version": "1.0.0",
                "url": "https://plugins.example/novelcool.js",
                "iconUrl": "https://plugins.example/novelcool.png"
              },
              {
                "id": "wuxiaworld", "name": "WuxiaWorld",
                "site": "https://wuxia.example",
                "lang": "en", "version": "1.0.1",
                "url": "https://plugins.example/wuxia.js"
              }
            ]
        """.trimIndent()
        val result = RepoFormatDetector.detect(body)
        assertTrue(result is RepoFormat.ForeignRepo)
        val foreign = result as RepoFormat.ForeignRepo
        assertEquals("LNReader", foreign.ecosystemName)
        assertEquals(
            listOf("https://novelcool.example", "https://wuxia.example"),
            foreign.sites.map { it.baseUrl }
        )
        assertEquals("NovelCool", foreign.sites.first().name)
    }

    @Test
    fun `lnreader object form with nested plugins is recognised`() {
        val body = """
            {
              "plugins": [
                {
                  "id": "royalroad", "name": "RoyalRoad",
                  "siteUrl": "https://royal.example", "lang": "en"
                }
              ]
            }
        """.trimIndent()
        val result = detectRepoFormat(body)
        assertTrue(result is RepoFormat.ForeignRepo)
        val foreign = result as RepoFormat.ForeignRepo
        assertEquals("LNReader", foreign.ecosystemName)
        assertEquals(listOf("https://royal.example"), foreign.sites.map { it.baseUrl })
    }

    @Test
    fun `legado array is recognised from bookSource fields`() {
        val body = """
            [
              {
                "bookSourceName": "Legado One",
                "bookSourceUrl": "https://legado-one.example",
                "searchUrl": "https://legado-one.example/search?key={{key}}"
              },
              {
                "bookSourceName": "Legado Two",
                "bookSourceUrl": "https://legado-two.example"
              }
            ]
        """.trimIndent()
        val result = detectRepoFormat(body)
        assertTrue(result is RepoFormat.ForeignRepo)
        val foreign = result as RepoFormat.ForeignRepo
        assertEquals("Legado", foreign.ecosystemName)
        assertEquals(
            listOf("https://legado-one.example", "https://legado-two.example"),
            foreign.sites.map { it.baseUrl }
        )
        assertEquals("Legado One", foreign.sites.first().name)
    }

    @Test
    fun `generic url-bearing list is another reader app`() {
        // No name/id fields (so it is not LNReader-shaped) — just
        // http(s) URLs in url-shaped fields, the generic marker.
        val body = """
            [
              {"baseUrl": "https://generic-one.example",
                "homepage": "https://generic-one.example"},
              {"baseUrl": "https://generic-two.example"}
            ]
        """.trimIndent()
        val result = detectRepoFormat(body)
        assertTrue(result is RepoFormat.ForeignRepo)
        val foreign = result as RepoFormat.ForeignRepo
        assertEquals("another reader app", foreign.ecosystemName)
        assertEquals(
            listOf("https://generic-one.example", "https://generic-two.example"),
            foreign.sites.map { it.baseUrl }
        )
    }

    @Test
    fun `binary protobuf-style index is named honestly`() {
        // A .pb index decoded as UTF-8 is dense with U+FFFD
        // replacement marks (Novel Sourcery's index.pb is the
        // verified shape). It must not degrade to the generic
        // not-JSON reason — the link IS a repository.
        val body = "�".repeat(24) + "garbled binary tail"
        val result = detectRepoFormat(body)
        assertTrue(result is RepoFormat.Unknown)
        assertTrue(
            (result as RepoFormat.Unknown).reason.contains("binary")
        )
    }

    @Test
    fun `unknown json and non-json are Unknown`() {
        assertTrue(detectRepoFormat("""{"foo": "bar"}""") is RepoFormat.Unknown)
        assertTrue(detectRepoFormat("""[{"foo": 1}, {"bar": true}]""") is RepoFormat.Unknown)
        assertTrue(detectRepoFormat("not json at all") is RepoFormat.Unknown)
        assertTrue(detectRepoFormat("{broken") is RepoFormat.Unknown)
        assertTrue(detectRepoFormat("") is RepoFormat.Unknown)
        assertTrue(detectRepoFormat("""[]""") is RepoFormat.Unknown)
        val reason = (detectRepoFormat("hello") as RepoFormat.Unknown).reason
        assertTrue(reason.isNotBlank())
    }

    @Test
    fun `xreader index shape is not misclassified as foreign`() {
        val body = """
            {
              "name": "Community Repo",
              "packages": [
                {
                  "name": "Novels Example", "pkg": "com.example.novels",
                  "versionName": "1.2", "versionCode": 3, "kind": "declarative",
                  "languages": ["en"],
                  "permissions": {"domains": ["novels.example"]},
                  "sources": [${RepoIndexTest.declarativeSourceJson()}]
                }
              ]
            }
        """.trimIndent()
        val result = detectRepoFormat(body)
        assertEquals(
            "a packages object the Xreader parser accepts is Xreader's own, " +
                "even though its sources carry baseUrl fields",
            RepoFormat.XreaderIndex,
            result
        )
    }
}
