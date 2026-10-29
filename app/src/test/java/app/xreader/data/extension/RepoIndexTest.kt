package app.xreader.data.extension

import app.xreader.core.error.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repo index parsing (PLAN §5 gate): valid catalogues survive,
 * unusable entries are dropped (never crash the whole list), unknown
 * kinds are skipped, and a malformed document fails with a typed
 * error — not an exception.
 */
class RepoIndexTest {

    @Test
    fun `parses a mixed catalogue`() {
        val body = """
            {
              "name": "Community Repo",
              "packages": [
                {
                  "name": "Novels Example", "pkg": "com.example.novels",
                  "versionName": "1.2", "versionCode": 3, "kind": "declarative",
                  "languages": ["en"],
                  "permissions": {"domains": ["novels.example"]},
                  "sources": [${declarativeSourceJson()}]
                },
                {
                  "name": "Scripted", "pkg": "com.example.js",
                  "versionName": "2.0", "versionCode": 5, "kind": "js",
                  "artifactUrl": "https://repo.example/js/bundle.js",
                  "sha256": "abc123",
                  "sourcesMeta": [{"id": "s1", "name": "S1", "lang": "en",
                    "baseUrl": "https://s1.example"}]
                },
                {
                  "name": "Future Kind", "pkg": "com.example.future",
                  "kind": "wasm", "sources": []
                },
                {
                  "name": "Broken Dex", "pkg": "com.example.dex",
                  "kind": "dex", "artifactUrl": "https://repo.example/x.apk",
                  "sha256": ""
                }
              ]
            }
        """.trimIndent()
        val result = parseRepoIndex(body)
        assertTrue(result is AppResult.Ok)
        val index = (result as AppResult.Ok).value
        assertEquals("Community Repo", index.name)
        assertEquals(
            "unknown kinds and unsafe entries are dropped",
            listOf("com.example.novels", "com.example.js"),
            index.packages.map { it.pkg }
        )
        val declarative = index.packages.first()
        assertEquals(ExtensionKind.DECLARATIVE, declarative.kindEnum)
        assertEquals(listOf("novels-example"), declarative.sourceIds)
        // The source's own host is folded into the declared domains.
        assertTrue(
            declarative.installPermissions().domains.contains("novels.example")
        )
    }

    @Test
    fun `malformed json is a typed error`() {
        val result = parseRepoIndex("{not really json")
        assertTrue(result is AppResult.Err)
    }

    @Test
    fun `empty index parses to an empty catalogue`() {
        val result = parseRepoIndex("""{"name":"Empty","packages":[]}""")
        assertTrue(result is AppResult.Ok)
        assertTrue((result as AppResult.Ok).value.packages.isEmpty())
    }

    @Test
    fun `declarative fingerprint changes with any rule change`() {
        val config = FixtureSite.config()
        val original = DeclarativeFingerprints.of(listOf(config))
        val tweaked = config.copy(
            chapter = config.chapter.copy(content = "div.other")
        )
        assertFalse(
            original == DeclarativeFingerprints.of(listOf(tweaked))
        )
        // And is stable for identical rules.
        assertEquals(original, DeclarativeFingerprints.of(listOf(config)))
    }

    companion object {
        fun declarativeSourceJson(): String = """
            {
              "id": "novels-example", "name": "Novels Example", "lang": "en",
              "baseUrl": "https://novels.example",
              "details": {
                "chapters": {"list": "ul li", "name": "a", "link": "a@href"}
              },
              "chapter": {"content": "div.content"}
            }
        """.trimIndent()
    }
}
