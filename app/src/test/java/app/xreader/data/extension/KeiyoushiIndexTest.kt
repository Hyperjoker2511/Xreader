package app.xreader.data.extension

import app.xreader.core.error.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manga programme (M1): the Keiyoushi index reader — both
 * published shapes (the tachiyomix object form verified live
 * 2026-11-01, and the classic Mihon array form), the rating
 * mapping (the index's own three warnings; nsfw flags with
 * the mixed-signal rule), conversion states that never claim
 * a site works before its conversion exists, and the
 * content-preference filter over the catalogue rows.
 * Fixtures are synthetic miniatures of the verified shapes.
 */
class KeiyoushiIndexTest {

    // ── shape 1: the tachiyomix object form (live shape) ────

    private val tachiyomixFixture = """
        {
          "name": "Keiyoushi",
          "extensionList": {
            "extensions": [
              {
                "name": "Safe Panels",
                "packageName": "eu.kanade.tachiyomi.extension.en.safepanels",
                "contentWarning": "CONTENT_WARNING_SAFE",
                "versionName": "1.0",
                "sources": [
                  {"id": "11", "name": "Safe Panels", "language": "en",
                   "homeUrl": "https://safepanels.example"}
                ]
              },
              {
                "name": "Mixed Ink",
                "packageName": "eu.kanade.tachiyomi.extension.all.mixedink",
                "contentWarning": "CONTENT_WARNING_MIXED",
                "sources": [
                  {"id": "21", "name": "Mixed Ink", "language": "all",
                   "homeUrl": "https://mixedink.example",
                   "mirrorUrls": ["https://mixedink-mirror.example"]}
                ]
              },
              {
                "name": "After Dark",
                "packageName": "eu.kanade.tachiyomi.extension.en.afterdark",
                "contentWarning": "CONTENT_WARNING_NSFW",
                "sources": [
                  {"id": "31", "name": "After Dark", "language": "en",
                   "homeUrl": "https://afterdark.example"},
                  {"id": "32", "name": "After Dark ES", "language": "es",
                   "homeUrl": "https://afterdark.example"}
                ]
              },
              {
                "name": "Broken Entry",
                "packageName": "eu.kanade.tachiyomi.extension.en.broken",
                "contentWarning": "CONTENT_WARNING_SAFE",
                "sources": [
                  {"id": "41", "name": "", "language": "en",
                   "homeUrl": "https://broken.example"},
                  {"id": "42", "name": "No Address", "language": "en"}
                ]
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `tachiyomix index parses entries with ratings langs and mirrors`() {
        val result = KeiyoushiIndex.parse(tachiyomixFixture)
        assertTrue(result is AppResult.Ok)
        val entries = (result as AppResult.Ok).value
        // 4 usable entries; the broken extension contributes none.
        assertEquals(4, entries.size)

        val safe = entries.single { it.name == "Safe Panels" }
        assertEquals(ContentRating.SAFE, safe.rating)
        assertEquals("en", safe.lang)
        assertEquals("safepanels.example", safe.host)

        val mixed = entries.single { it.name == "Mixed Ink" }
        assertEquals(ContentRating.MIXED, mixed.rating)
        assertEquals(
            listOf("https://mixedink-mirror.example"), mixed.mirrorUrls
        )

        val adult = entries.filter { it.host == "afterdark.example" }
        assertEquals(2, adult.size)
        assertTrue(adult.all { it.rating == ContentRating.ADULT })
        assertEquals(
            "eu.kanade.tachiyomi.extension.en.afterdark",
            adult.first().packageName
        )
    }

    // ── shape 2: the classic Mihon array form ───────────────

    private val classicFixture = """
        [
          {"name": "Flagged House", "pkg": "eu.kanade.tachiyomi.extension.en.flagged",
           "apk": "tachiyomi-en.flagged.apk", "nsfw": 1, "version": "1.0",
           "sources": [
             {"name": "Flagged Main", "lang": "en", "id": "51",
              "baseUrl": "https://flagged.example", "nsfw": 1},
             {"name": "Flagged Clean Corner", "lang": "en", "id": "52",
              "baseUrl": "https://clean.flagged.example", "nsfw": 0},
             {"name": "Flagged Unmarked", "lang": "en", "id": "53",
              "baseUrl": "https://unmarked.flagged.example"}
           ]},
          {"name": "Quiet Shelf", "pkg": "eu.kanade.tachiyomi.extension.en.quiet",
           "apk": "tachiyomi-en.quiet.apk", "nsfw": 0, "version": "1.0",
           "sources": [
             {"name": "Quiet Shelf", "lang": "en", "id": "61",
              "baseUrl": "https://quiet.example"}
           ]}
        ]
    """.trimIndent()

    @Test
    fun `classic mihon index maps nsfw flags with the mixed-signal rule`() {
        val result = KeiyoushiIndex.parse(classicFixture)
        assertTrue(result is AppResult.Ok)
        val entries = (result as AppResult.Ok).value
        assertEquals(4, entries.size)
        val byName = entries.associateBy { it.name }
        // Flagged itself → 18+.
        assertEquals(
            ContentRating.ADULT, byName["Flagged Main"]!!.rating
        )
        // Unflagged inside a flagged package → mixed signals.
        assertEquals(
            ContentRating.MIXED, byName["Flagged Clean Corner"]!!.rating
        )
        // No flag of its own inside a flagged package → the
        // package's flag governs → 18+.
        assertEquals(
            ContentRating.ADULT, byName["Flagged Unmarked"]!!.rating
        )
        // No flags anywhere → Safe.
        assertEquals(ContentRating.SAFE, byName["Quiet Shelf"]!!.rating)
    }

    @Test
    fun `the stub index and garbage are honest failures`() {
        val stub = """
            [
              {"name": "Outdated App", "pkg": "eu.kanade.tachiyomi.extension.all",
               "apk": "app.apk", "nsfw": 0,
               "sources": [{"name": "Outdated App", "lang": "all", "id": "1",
                            "baseUrl": "https://keiyoushi.github.io"}]}
            ]
        """.trimIndent()
        assertTrue(KeiyoushiIndex.parse(stub) is AppResult.Err)
        assertTrue(KeiyoushiIndex.parse("not json at all") is AppResult.Err)
        assertTrue(KeiyoushiIndex.parse("{}") is AppResult.Err)
        assertTrue(KeiyoushiIndex.parse("[]") is AppResult.Err)
    }

    @Test
    fun `rating mapping functions pin the three values`() {
        assertEquals(
            ContentRating.ADULT,
            KeiyoushiIndex.ratingForContentWarning("CONTENT_WARNING_NSFW")
        )
        assertEquals(
            ContentRating.MIXED,
            KeiyoushiIndex.ratingForContentWarning("CONTENT_WARNING_MIXED")
        )
        assertEquals(
            ContentRating.SAFE,
            KeiyoushiIndex.ratingForContentWarning("CONTENT_WARNING_SAFE")
        )
        assertEquals(
            ContentRating.SAFE,
            KeiyoushiIndex.ratingForContentWarning(null)
        )
        assertEquals(
            ContentRating.ADULT,
            KeiyoushiIndex.ratingForNsfw(packageNsfw = true, sourceNsfw = true)
        )
        assertEquals(
            ContentRating.MIXED,
            KeiyoushiIndex.ratingForNsfw(packageNsfw = true, sourceNsfw = false)
        )
        assertEquals(
            ContentRating.ADULT,
            KeiyoushiIndex.ratingForNsfw(packageNsfw = true, sourceNsfw = null)
        )
        assertEquals(
            ContentRating.SAFE,
            KeiyoushiIndex.ratingForNsfw(packageNsfw = false, sourceNsfw = false)
        )
    }

    // ── conversion state + rows ─────────────────────────────

    private fun convertedConfig(
        baseUrl: String,
        kind: MediaKind = MediaKind.MANGA
    ): DeclarativeConfig = MangaFixture.config().copy(
        id = "converted-${baseUrl.hashCode()}",
        baseUrl = baseUrl,
        mediaKind = kind
    )

    @Test
    fun `converted hosts come from manga configs only`() {
        val hosts = KeiyoushiIndex.convertedHosts(
            listOf(
                convertedConfig("https://safepanels.example"),
                convertedConfig(
                    "https://novels.example", kind = MediaKind.TEXT
                )
            )
        )
        assertEquals(setOf("safepanels.example"), hosts)
    }

    @Test
    fun `rows carry true states badges and the preference filter`() {
        val entries = (KeiyoushiIndex.parse(tachiyomixFixture)
            as AppResult.Ok).value
        val converted = KeiyoushiIndex.convertedHosts(
            listOf(convertedConfig("https://safepanels.example"))
        )

        val shownAll = KeiyoushiIndex.rows(
            entries, converted, ContentVisibility.SHOW_ALL
        )
        assertEquals(4, shownAll.size)
        val byName = shownAll.associateBy { it.entry.name }
        // The converted site is Ready; the rest honestly are not.
        assertEquals(
            KeiyoushiIndex.State.READY, byName["Safe Panels"]!!.state
        )
        assertEquals(
            "Ready", byName["Safe Panels"]!!.state.label
        )
        assertEquals(
            KeiyoushiIndex.State.NEEDS_CONVERSION,
            byName["Mixed Ink"]!!.state
        )
        assertEquals(
            "Needs the manga conversion",
            byName["Mixed Ink"]!!.state.label
        )
        assertEquals("Mixed", byName["Mixed Ink"]!!.badge)
        assertEquals("18+", byName["After Dark"]!!.badge)
        assertEquals(null, byName["Safe Panels"]!!.badge)

        // Default preference: 18+ entries are ABSENT, Mixed stays.
        val defaultRows = KeiyoushiIndex.rows(
            entries, converted, ContentVisibility.HIDE_ADULT
        )
        assertEquals(
            listOf("Mixed Ink", "Safe Panels"),
            defaultRows.map { it.entry.name }
        )
        // Strictest preference: only Safe remains.
        val safeRows = KeiyoushiIndex.rows(
            entries, converted, ContentVisibility.HIDE_MIXED_AND_ADULT
        )
        assertEquals(listOf("Safe Panels"), safeRows.map { it.entry.name })
    }

    // ── v1.4.3: the shelf (per-package rows + search) ──────

    private fun shelfEntry(
        name: String,
        lang: String,
        url: String,
        pkg: String
    ) = KeiyoushiIndex.Entry(
        name = name, lang = lang, baseUrl = url, packageName = pkg
    )

    @Test
    fun `shelf search ranks a name match above domain matches`() {
        val rows = KeiyoushiIndex.shelfRows(
            entries = listOf(
                // Shelf order puts this row FIRST (alphabetical),
                // and its domain contains the query — but its
                // NAME does not.
                shelfEntry(
                    "Aardvark Panels", "en",
                    "https://aardvarkcomics.example",
                    "eu.kanade.tachiyomi.extension.en.aardvark"
                ),
                shelfEntry(
                    "Comix", "en",
                    "https://comix.example",
                    "eu.kanade.tachiyomi.extension.en.comix"
                ),
                shelfEntry(
                    "Zebra Tales", "en",
                    "https://zebra.example",
                    "eu.kanade.tachiyomi.extension.en.zebra"
                )
            ),
            convertedHosts = emptySet(),
            visibility = ContentVisibility.SHOW_ALL,
            playerStates = emptyMap()
        )
        assertEquals(
            listOf("Aardvark Panels", "Comix", "Zebra Tales"),
            rows.map { it.name }
        )
        val found = KeiyoushiIndex.searchShelf(rows, "com")
        // The name match leads even though the domain match
        // stood above it on the shelf; the non-match is gone.
        assertEquals(
            listOf("Comix", "Aardvark Panels"),
            found.map { it.name }
        )
    }

    @Test
    fun `shelf search covers the whole shelf not a filtered subset`() {
        val rows = KeiyoushiIndex.shelfRows(
            entries = listOf(
                shelfEntry(
                    "Comix", "en",
                    "https://comix.example",
                    "eu.kanade.tachiyomi.extension.en.comix"
                ),
                shelfEntry(
                    "Luna Panels", "es",
                    "https://lunapanels.example",
                    "eu.kanade.tachiyomi.extension.es.luna"
                )
            ),
            convertedHosts = emptySet(),
            visibility = ContentVisibility.SHOW_ALL,
            playerStates = emptyMap()
        )
        // A language-filtered subset (what the old screen
        // searched) would never surface the Spanish row…
        val englishOnly = rows.filter { "en" in it.langs }
        assertTrue(
            KeiyoushiIndex.searchShelf(englishOnly, "luna").isEmpty()
        )
        // …the whole-shelf search does.
        assertEquals(
            listOf("Luna Panels"),
            KeiyoushiIndex.searchShelf(rows, "luna").map { it.name }
        )
        // A blank query is the shelf itself, untouched.
        assertEquals(rows, KeiyoushiIndex.searchShelf(rows, "  "))
    }

    @Test
    fun `shelf groups updates installed and available`() {
        val pkg = "eu.kanade.tachiyomi.extension.en.comix"
        val rows = KeiyoushiIndex.shelfRows(
            entries = listOf(
                shelfEntry(
                    "Comix", "en", "https://comix.example", pkg
                ),
                shelfEntry(
                    "Fresh Ink", "en",
                    "https://freshink.example",
                    "eu.kanade.tachiyomi.extension.en.freshink"
                )
            ),
            convertedHosts = emptySet(),
            visibility = ContentVisibility.SHOW_ALL,
            playerStates = mapOf(
                pkg to KeiyoushiIndex.PlayerPackageState(
                    KeiyoushiIndex.PlayerPackageState.Kind
                        .UPDATE_AVAILABLE
                )
            )
        )
        val comix = rows.single { it.name == "Comix" }
        assertEquals(
            KeiyoushiIndex.ShelfGroup.UPDATES,
            KeiyoushiIndex.shelfGroupOf(comix)
        )
        assertEquals(KeiyoushiIndex.RowAction.UPDATE, comix.action)
        val fresh = rows.single { it.name == "Fresh Ink" }
        assertEquals(
            KeiyoushiIndex.ShelfGroup.AVAILABLE,
            KeiyoushiIndex.shelfGroupOf(fresh)
        )
        // One verb for every not-installed row: Install.
        assertEquals(KeiyoushiIndex.RowAction.INSTALL, fresh.action)
        assertEquals(KeiyoushiIndex.State.NOT_INSTALLED, fresh.state)
    }

    // ── v1.4.4: unsupported-library shelf honesty ──────────

    private fun shelfEntryLib(
        name: String,
        url: String,
        pkg: String,
        lib: String
    ) = KeiyoushiIndex.Entry(
        name = name, lang = "en", baseUrl = url,
        packageName = pkg, extensionLib = lib
    )

    @Test
    fun `shelf marks a too-new library plainly and offers no install`() {
        val rows = KeiyoushiIndex.shelfRows(
            entries = listOf(
                shelfEntryLib(
                    "Future Panels", "https://future.example",
                    "eu.kanade.tachiyomi.extension.en.future", "1.7"
                ),
                shelfEntryLib(
                    "Current Panels", "https://current.example",
                    "eu.kanade.tachiyomi.extension.en.current", "1.6"
                ),
                shelfEntryLib(
                    "Silent Panels", "https://silent.example",
                    "eu.kanade.tachiyomi.extension.en.silent", ""
                )
            ),
            convertedHosts = emptySet(),
            visibility = ContentVisibility.SHOW_ALL,
            playerStates = emptyMap()
        )
        val future = rows.single { it.name == "Future Panels" }
        assertEquals(
            KeiyoushiIndex.State.UNSUPPORTED_LIB, future.state
        )
        assertEquals("Needs a newer Xreader", future.state.label)
        assertEquals(KeiyoushiIndex.RowAction.NONE, future.action)
        assertEquals(
            KeiyoushiIndex.ShelfGroup.AVAILABLE,
            KeiyoushiIndex.shelfGroupOf(future)
        )
        assertTrue(future.detail!!.contains("1.7"))
        assertTrue(future.detail!!.contains("1.6"))
        // The supported and the undeclared keep their Install.
        for (name in listOf("Current Panels", "Silent Panels")) {
            val row = rows.single { it.name == name }
            assertEquals(KeiyoushiIndex.State.NOT_INSTALLED, row.state)
            assertEquals(KeiyoushiIndex.RowAction.INSTALL, row.action)
        }
    }

    @Test
    fun `an installed package is never re-judged by the index lib`() {
        val pkg = "eu.kanade.tachiyomi.extension.en.future"
        val rows = KeiyoushiIndex.shelfRows(
            entries = listOf(
                shelfEntryLib(
                    "Future Panels", "https://future.example", pkg, "1.7"
                )
            ),
            convertedHosts = emptySet(),
            visibility = ContentVisibility.SHOW_ALL,
            playerStates = mapOf(
                pkg to KeiyoushiIndex.PlayerPackageState(
                    KeiyoushiIndex.PlayerPackageState.Kind.READY
                )
            )
        )
        val row = rows.single()
        assertEquals(KeiyoushiIndex.State.READY, row.state)
        assertEquals(KeiyoushiIndex.RowAction.REMOVE, row.action)
    }

    @Test
    fun `lib comparison is numeric per part not lexicographic`() {
        assertTrue(KeiyoushiIndex.libExceedsSupported("1.7"))
        assertTrue(KeiyoushiIndex.libExceedsSupported("1.10"))
        assertTrue(KeiyoushiIndex.libExceedsSupported("2.0"))
        assertTrue(!KeiyoushiIndex.libExceedsSupported("1.6"))
        assertTrue(!KeiyoushiIndex.libExceedsSupported("1.5"))
        // Unknown declarations never block the shelf.
        assertTrue(!KeiyoushiIndex.libExceedsSupported(""))
        assertTrue(!KeiyoushiIndex.libExceedsSupported("beta"))
    }
}
