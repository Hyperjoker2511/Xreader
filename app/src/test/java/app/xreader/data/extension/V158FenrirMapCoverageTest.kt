package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (B5): the widened Fenrir type map. Every plausible
 * manga-side value the site emits (webtoon, manhwa, comic,
 * manga) files MANGA through the tier-1 map AND through the
 * real JSON listing parse; the text side is unchanged; an
 * unmapped value files nothing and is logged ONCE per
 * (source, value) under the `kind` tag — the diagnostic that
 * turns the next taxonomy surprise into a visible fact.
 *
 * The details-document road for the same values is B1's
 * evidence (Builder 2's parse work); this test pins the map,
 * the listing birth, and the log seam the parse sites call.
 */
class V158FenrirMapCoverageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun widenedValuesFileThroughTheMap() {
        for (value in listOf("webtoon", "manhwa", "comic", "manga")) {
            assertEquals(
                "$value must file MANGA",
                BookKind152.MANGA,
                BookKind152.tier1(BookKind152.FENRIR_TYPE_MAP, value)
            )
        }
        for (value in listOf("novel", "web_novel", "light_novel", "original_novel")) {
            assertEquals(
                "$value must file TEXT",
                BookKind152.TEXT,
                BookKind152.tier1(BookKind152.FENRIR_TYPE_MAP, value)
            )
        }
        assertNull(BookKind152.tier1(BookKind152.FENRIR_TYPE_MAP, "light_comic"))
    }

    @Test
    fun widenedValuesFileAtListingBirthThroughTheRealParse() = runBlocking {
        val listing = """
            {"data":[
              {"title":"Toon Tale","slug":"/series/toon","type":"webtoon"},
              {"title":"Hwa Tale","slug":"/series/hwa","type":"manhwa"},
              {"title":"Panel Tale","slug":"/series/panel","type":"comic"},
              {"title":"Word Tale","slug":"/series/word","type":"novel"}
            ]}
        """.trimIndent()
        val config = DeclarativeConfig(
            id = "kindmap",
            name = "kindmap",
            baseUrl = "https://kindmap.example",
            popular = ListSpec(
                url = "https://kindmap.example/api/list",
                results = "",
                title = "title",
                link = "slug",
                jsonPath = "data",
                kindField = "type",
                kindValueMap = BookKind152.FENRIR_TYPE_MAP
            ),
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "li", name = "a", link = "a@href",
                    jsonPath = "data"
                )
            ),
            chapter = ChapterSpec(content = ".content")
        )
        val transport = FakeTransport {
            AppResult.Ok(FakeTransport.response(200, listing))
        }
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        val context = factory.contextFor(
            extensionRowId = 1,
            packageId = "test.kindmap",
            label = "kindmap",
            permissions = {
                ExtensionPermissions(domains = listOf("kindmap.example"))
            }
        )
        val page = withContext(Dispatchers.IO) {
            DeclarativeSource(config, context).popular(1)
        }
        val byTitle = page.books.associateBy { it.title }
        assertEquals(BookKind152.MANGA, byTitle.getValue("Toon Tale").kind)
        assertEquals(BookKind152.MANGA, byTitle.getValue("Hwa Tale").kind)
        assertEquals(BookKind152.MANGA, byTitle.getValue("Panel Tale").kind)
        assertEquals(BookKind152.TEXT, byTitle.getValue("Word Tale").kind)
    }

    @Test
    fun unmappedValueLogsOncePerSourceAndValue() {
        DiagnosticLog.clear()
        UnmappedKindLog.resetForTest()
        UnmappedKindLog.record("fenrir", "light_comic")
        UnmappedKindLog.record("fenrir", "light_comic")
        UnmappedKindLog.record("othersource", "light_comic")
        val entries = DiagnosticLog.entries().filter { it.tag == "kind" }
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.message.contains("light_comic") })
        assertEquals(
            setOf("fenrir", "othersource"),
            entries.map { it.detail }.toSet()
        )
    }
}
