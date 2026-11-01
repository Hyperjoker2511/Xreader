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
}
