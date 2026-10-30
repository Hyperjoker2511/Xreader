package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * v1.0.9 pins:
 *
 * 1. Repo-link normalisation — every paste shape the other
 *    apps accept resolves to candidate index URLs, best first.
 * 2. The tachiyomix formats — the JSON twin (index.json) and
 *    the binary index.pb both yield the repo's site list, and
 *    a stub index (upgrade nags only) is flagged as a stub.
 * 3. LNReader family conversion — the constructor-options
 *    reader and the three family templates.
 * 4. Live Sources — a site installed as-is serves catalogue,
 *    book, chapters and chapter text derived live from its
 *    pages, with nothing saved but its address.
 * 5. The seed catalogue names known hosts.
 */
class V109RepoLiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 1. link normalisation ───────────────────────────────

    @Test
    fun `normaliser handles the paste shapes`() {
        // A full index URL is tried first, then its siblings.
        val ln = RepoLinkNormaliser.candidates(
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/" +
                "plugins/v3.0.0/.dist/plugins.min.json"
        )
        assertEquals(
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/" +
                "plugins/v3.0.0/.dist/plugins.min.json",
            ln.first()
        )
        // A GitHub repo PAGE resolves to the raw repo branch.
        val page = RepoLinkNormaliser.candidates(
            "https://github.com/novelsourcery/extensions"
        )
        assertEquals(
            "https://raw.githubusercontent.com/novelsourcery/extensions/repo/repo.json",
            page.first()
        )
        assertTrue(page.any { it.endsWith("/repo/index.pb") })
        // The github.com/…/raw/… form of the same repo.
        val raw = RepoLinkNormaliser.candidates(
            "https://github.com/novelsourcery/extensions/raw/repo/index.pb"
        )
        assertEquals(
            "https://raw.githubusercontent.com/novelsourcery/extensions/repo/index.pb",
            raw.first()
        )
        // owner/repo shorthand.
        val shorthand = RepoLinkNormaliser.candidates("skepsun/kototoro-parsers")
        assertEquals(
            "https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/repo.json",
            shorthand.first()
        )
        // A deep link unwraps to its index URL.
        val deep = RepoLinkNormaliser.candidates(
            "tachiyomi://add-repo?url=https%3A%2F%2Fexample.com%2Frepo%2Findex.json"
        )
        assertEquals("https://example.com/repo/index.json", deep.first())
        // A bare directory is probed in index order.
        val dir = RepoLinkNormaliser.candidates("https://example.com/repo")
        assertEquals(
            listOf(
                "https://example.com/repo/repo.json",
                "https://example.com/repo/index.json",
                "https://example.com/repo/index.pb",
                "https://example.com/repo/index.min.json"
            ),
            dir
        )
        // A bare host is a directory, never a "file".
        val host = RepoLinkNormaliser.candidates("https://example.com")
        assertEquals("https://example.com/repo.json", host.first())
    }

    // ── 2. tachiyomix formats ───────────────────────────────

    @Test
    fun `tachiyomix json index yields its site list`() {
        val body = """
            {
              "name": "TestSourcery",
              "badgeLabel": "TS",
              "signingKey": "abc123",
              "contact": {"website": "https://example.com"},
              "extensionList": {
                "extensions": [
                  {
                    "name": "WTR-LAB",
                    "packageName": "eu.kanade.tachiyomi.novelextension.all.wtrlab",
                    "resources": {"apkUrl": "https://x/a.apk", "iconUrl": "https://x/i.png", "jarUrl": "https://x/a.jar"},
                    "extensionLib": "1.6",
                    "versionCode": "106005",
                    "versionName": "1.6.5",
                    "contentWarning": "CONTENT_WARNING_SAFE",
                    "sources": [
                      {"id": "113591650919252840", "name": "WTR-LAB", "language": "en", "homeUrl": "https://wtr-lab.com"},
                      {"id": "223591650919252841", "name": "WTR-LAB", "language": "ar", "homeUrl": "https://wtr-lab.com"}
                    ],
                    "isNovel": true
                  },
                  {
                    "name": "Paradise",
                    "packageName": "eu.kanade.tachiyomi.novelextension.all.paradise",
                    "resources": {"apkUrl": "https://x/b.apk", "iconUrl": "https://x/j.png", "jarUrl": "https://x/b.jar"},
                    "extensionLib": "1.6",
                    "versionCode": "106006",
                    "versionName": "1.6.6",
                    "contentWarning": "CONTENT_WARNING_MIXED",
                    "sources": [
                      {"id": "6366234267253759405", "name": "NovelsParadise", "language": "ar", "homeUrl": "https://www.novelsparadise.site", "mirrorUrls": ["https://novelsparadise.site"]}
                    ],
                    "isNovel": true
                  }
                ]
              }
            }
        """.trimIndent()
        val format = RepoFormatDetector.detect(body)
        val foreign = format as? RepoFormat.ForeignRepo
        assertNotNull("tachiyomix JSON must classify as foreign repo", foreign)
        assertEquals("TestSourcery", foreign!!.ecosystemName)
        // wtr-lab.com appears twice (two languages) — deduped by URL.
        assertEquals(2, foreign.sites.size)
        assertEquals("https://wtr-lab.com", foreign.sites[0].baseUrl)
        assertFalse(foreign.pluginIndex)
        assertFalse(foreign.stubIndex)
    }

    @Test
    fun `stub index is flagged and a real mihon index is not`() {
        val stub = """
            [
              {"name": "Outdated App", "pkg": "eu.kanade.tachiyomi.extension.all.keiyoushi",
               "apk": "a.apk", "lang": "all", "code": 1, "version": "1.4.1", "nsfw": 0,
               "sources": [{"name": "Outdated App", "lang": "all", "id": "1", "baseUrl": "https://novelsourcery.github.io"}]},
              {"name": "Update to Mihon 0.3.1+", "pkg": "eu.kanade.tachiyomi.extension.all.mihon",
               "apk": "b.apk", "lang": "all", "code": 1, "version": "1.4.1", "nsfw": 0,
               "sources": [{"name": "Update to Tsundoku 0.3.1+", "lang": "all", "id": "1", "baseUrl": "https://tsundoku-otaku.github.io/"}]}
            ]
        """.trimIndent()
        val stubFormat = RepoFormatDetector.detect(stub) as? RepoFormat.ForeignRepo
        assertNotNull(stubFormat)
        assertTrue("an all-nag index is a stub", stubFormat!!.stubIndex)

        val real = """
            [
              {"name": "Keiyoushi", "pkg": "eu.kanade.tachiyomi.extension.en.real",
               "apk": "c.apk", "lang": "en", "code": 12, "version": "1.4.12", "nsfw": 0,
               "sources": [{"name": "Real Site", "lang": "en", "id": "7", "baseUrl": "https://real.example"}]}
            ]
        """.trimIndent()
        val realFormat = RepoFormatDetector.detect(real) as? RepoFormat.ForeignRepo
        assertNotNull(realFormat)
        assertFalse(realFormat!!.stubIndex)
    }

    @Test
    fun `binary tachiyomix index yields its site list`() {
        val bytes = encodeTachiyomixIndex()
        val index = PbIndexReader.read(bytes)
        assertNotNull(index)
        assertEquals("NovelSourcery", index!!.name)
        assertEquals(1, index.extensions.size)
        assertEquals(106005L, index.extensions[0].versionCode)
        assertEquals(2, index.extensions[0].sources.size)
        assertEquals(
            listOf("https://novelsparadise.site"),
            index.extensions[0].sources[0].mirrorUrls
        )
        val foreign = RepoFormatDetector.detectPb(bytes)
        assertNotNull(foreign)
        assertEquals("NovelSourcery", foreign!!.ecosystemName)
        assertEquals(2, foreign.sites.size)
        assertNull(PbIndexReader.read(byteArrayOf(0x0A, 0x05, 0x41)))
    }

    /** A hand-encoded tachiyomix index (the wire helpers mirror
     * the verified schema's field numbers). */
    private fun encodeTachiyomixIndex(): ByteArray {
        fun varint(out: ByteArrayOutputStream, v: Long) {
            var x = v
            while (true) {
                val b = (x and 0x7F).toInt()
                x = x ushr 7
                if (x == 0L) {
                    out.write(b)
                    return
                } else {
                    out.write(b or 0x80)
                }
            }
        }
        fun tag(out: ByteArrayOutputStream, field: Int, wire: Int) =
            varint(out, ((field shl 3) or wire).toLong())
        fun str(out: ByteArrayOutputStream, field: Int, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            tag(out, field, 2)
            varint(out, b.size.toLong())
            out.write(b)
        }
        fun msg(out: ByteArrayOutputStream, field: Int, body: ByteArray) {
            tag(out, field, 2)
            varint(out, body.size.toLong())
            out.write(body)
        }
        fun num(out: ByteArrayOutputStream, field: Int, v: Long) {
            tag(out, field, 0)
            varint(out, v)
        }
        val source1 = ByteArrayOutputStream().apply {
            num(this, 1, 6366234267253759405L)
            str(this, 2, "NovelsParadise")
            str(this, 3, "ar")
            str(this, 4, "https://www.novelsparadise.site")
            str(this, 5, "https://novelsparadise.site")
        }.toByteArray()
        val source2 = ByteArrayOutputStream().apply {
            num(this, 1, 113591650919252840L)
            str(this, 2, "WTR-LAB")
            str(this, 3, "en")
            str(this, 4, "https://wtr-lab.com")
        }.toByteArray()
        val extension = ByteArrayOutputStream().apply {
            str(this, 1, "WTR-LAB")
            str(this, 2, "eu.kanade.tachiyomi.novelextension.all.wtrlab")
            num(this, 5, 106005L)
            str(this, 6, "1.6.5")
            num(this, 7, 1L)
            msg(this, 8, source1)
            msg(this, 8, source2)
        }.toByteArray()
        val list = ByteArrayOutputStream().apply {
            msg(this, 1, extension)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            str(this, 1, "NovelSourcery")
            str(this, 3, "4281820d")
            msg(this, 101, list)
        }.toByteArray()
    }

    // ── 3. LNReader families ────────────────────────────────

    @Test
    fun `constructor options are read from compiled plugins`() {
        val madaraTail = """
            var g=new b({id:"arnovel",sourceSite:"https://ar-no.com/",sourceName:"ArNovel",options:{useNewChapterEndpoint:!0,lang:"Arabic"},filters:{adult:{type:"Picker",value:""}}});
            exports.default=g;
        """.trimIndent()
        val madara = JsConstructorOptions.extractOptions(madaraTail)
        assertNotNull(madara)
        assertEquals("true", madara!!["useNewChapterEndpoint"])
        assertEquals("Arabic", madara["lang"])

        val rnfTail = """
            exports.default = new ReadNovelFullPlugin({id:"allnovel", sourceSite:"https://novelfull.com/", sourceName:"AllNovel", options:{latestPage:"latest-release-novel", searchPage:"search", chapterListing:"ajax-chapter-option", versionIncrements:2}, filters:{}});
        """.trimIndent()
        val rnf = JsConstructorOptions.extractOptions(rnfTail)
        assertNotNull(rnf)
        assertEquals("latest-release-novel", rnf!!["latestPage"])
        assertEquals("2", rnf["versionIncrements"])
        assertNull(JsConstructorOptions.extractOptions("var x = 1;"))
    }

    @Test
    fun `family detection and templates`() {
        assertEquals(
            "madara",
            FamilyTemplates.familyOf(
                "https://raw.example/.js/src/plugins/arabic/ArNovel[madara].js"
            )
        )
        assertEquals(
            "fictioneer",
            FamilyTemplates.familyOf("https://raw.example/X[fictioneer].js")
        )
        assertNull(FamilyTemplates.familyOf("https://raw.example/plain.js"))
        assertTrue(FamilyTemplates.hasTemplate("madara"))
        assertFalse(FamilyTemplates.hasTemplate("fictioneer"))
        assertFalse(FamilyTemplates.hasTemplate(null))

        val madara = FamilyTemplates.configFor(
            family = "madara", pluginId = "arnovel",
            site = "https://ar-no.com/", name = "ArNovel", lang = "ar",
            options = mapOf("useNewChapterEndpoint" to "true")
        )!!
        assertEquals("lnreader.arnovel", madara.id)
        assertEquals(
            "https://ar-no.com/?m_orderby=views&page={page}",
            madara.popular!!.url
        )
        assertEquals(
            "https://ar-no.com?s={query}&post_type=wp-manga&m_orderby=views",
            madara.search!!.url
        )
        assertTrue(madara.details.chapters.reverse)
        assertEquals(
            ".reading-content .text-left || .text-left || .reading-content",
            madara.chapter.content
        )

        val madaraPrefixed = FamilyTemplates.configFor(
            family = "madara", pluginId = "some", site = "https://s.example",
            name = "S", lang = "en", options = mapOf("pathPrefix" to "series")
        )!!
        assertEquals(
            "https://s.example/series/?m_orderby=views&page={page}",
            madaraPrefixed.popular!!.url
        )

        val lnwp = FamilyTemplates.configFor(
            family = "lightnovelwp", pluginId = "freekolnovel",
            site = "https://free.kolnovel.com/", name = "Free Kol Novel",
            lang = "ar", options = mapOf("novelSlug" to "kol-novel")
        )!!
        assertEquals(
            "https://free.kolnovel.com/kol-novel/page/{page}/?m_orderby=views",
            lnwp.popular!!.url
        )

        val rnf = FamilyTemplates.configFor(
            family = "readnovelfull", pluginId = "allnovel",
            site = "https://novelfull.com/", name = "AllNovel", lang = "en",
            options = mapOf(
                "latestPage" to "latest-release-novel",
                "searchPage" to "search"
            )
        )!!
        assertEquals(
            "https://novelfull.com/latest-release-novel?page={page}",
            rnf.popular!!.url
        )
        assertEquals(
            "https://novelfull.com/search?keyword={query}",
            rnf.search!!.url
        )
        assertEquals("#chapter-content || .chapter-content || .content", rnf.chapter.content)
        assertNull(
            FamilyTemplates.configFor(
                "fictioneer", "x", "https://x.example", "X", "en", emptyMap()
            )
        )
    }

    @Test
    fun `lnreader entries carry their family`() {
        val body = """
            [
              {"id":"arnovel","name":"ArNovel","site":"https://ar-no.com/","lang":"ar","version":"2.3.0","url":"https://raw.example/.js/src/plugins/arabic/ArNovel[madara].js","iconUrl":"https://raw.example/icon.png"},
              {"id":"handmade","name":"Hand Made","site":"https://hand.example/","lang":"en","version":"1.0.0","url":"https://raw.example/.js/src/plugins/english/HandMade.js","iconUrl":"https://raw.example/icon2.png"}
            ]
        """.trimIndent()
        val foreign = RepoFormatDetector.detect(body) as? RepoFormat.ForeignRepo
        assertNotNull(foreign)
        assertEquals("LNReader", foreign!!.ecosystemName)
        val arnovel = foreign.sites.first { it.name == "ArNovel" }
        assertEquals("madara", arnovel.family)
        assertEquals("arnovel", arnovel.pluginId)
        assertNotNull(arnovel.pluginUrl)
        val handmade = foreign.sites.first { it.name == "Hand Made" }
        assertNull(handmade.family)
    }

    // ── 4. Live Sources ─────────────────────────────────────

    private val prose = (
        "The lighthouse keeper climbed the iron stairs before dawn, as he had " +
            "every morning for eleven years. The lamp still burned against the " +
            "grey sea. He trimmed the wick and watched the gulls settle on the " +
            "rocks below the tower. A letter had come with the supply boat, and " +
            "its seal was already broken. Whatever it said, the light came " +
            "first. It always had. He lit the second lamp and began his rounds."
        )

    private fun liveTransport(): FakeTransport = FakeTransport { request ->
        val url = request.url
        val listing = """
            <html><body><div class="grid">
            <a href="/novel/alpha"><img src="/a.jpg">Alpha Tale</a>
            <a href="/novel/beta"><img src="/b.jpg">Beta Tale</a>
            <a href="/novel/gamma"><img src="/g.jpg">Gamma Tale</a>
            </div></body></html>
        """.trimIndent()
        val book = """
            <html><body>
            <h1>Alpha Tale</h1>
            <img src="/cover.jpg">
            <ul class="chapters">
            <li><a href="/novel/alpha/3">Chapter 3</a></li>
            <li><a href="/novel/alpha/2">Chapter 2</a></li>
            <li><a href="/novel/alpha/1">Chapter 1</a></li>
            </ul>
            </body></html>
        """.trimIndent()
        val chapter = """
            <html><body><main><article>
            <h1>Chapter 1</h1><p>$prose</p>
            </article></main></body></html>
        """.trimIndent()
        when {
            url.contains("/search") -> FakeTransport.ok(listing)
            url.endsWith("/novel/alpha/1") || url.endsWith("/novel/alpha/2") ||
                url.endsWith("/novel/alpha/3") -> FakeTransport.ok(chapter)
            url.endsWith("/novel/alpha") -> FakeTransport.ok(book)
            url == "https://live.example/" || url == "https://live.example" ->
                FakeTransport.ok(listing)
            else -> AppResult.Ok(FakeTransport.response(404, "nope"))
        }
    }

    private fun liveSource(startUrl: String): LiveSource {
        val transport = liveTransport()
        val context = testContext(
            transport = transport,
            filesDir = tmp.newFolder(),
            permissions = ExtensionPermissions(domains = listOf("live.example"))
        )
        val spec = LiveSourceSpec(
            id = "live.live-example",
            name = "Live Example",
            baseUrl = "https://live.example",
            startUrl = startUrl
        )
        return LiveSource(spec, context)
    }

    @Test
    fun `live source serves catalogue book chapters and text`() {
        val source = liveSource("https://live.example/")
        val page = source.popular(1)
        assertEquals(3, page.books.size)
        assertEquals("Alpha Tale", page.books[0].title)

        val book = page.books[0]
        val details = source.details(book)
        assertEquals("Alpha Tale", details.title)

        val chapters = source.chapters(book)
        assertEquals(3, chapters.size)
        assertEquals(
            listOf("Chapter 1", "Chapter 2", "Chapter 3"),
            chapters.map { it.name }
        )

        val content = source.chapterContent(chapters[0])
        assertTrue(content.html.contains("lighthouse keeper"))

        val results = source.search("alpha", 1)
        assertEquals(3, results.books.size)
    }

    @Test
    fun `live source installed from a book link lists that book`() {
        val source = liveSource("https://live.example/novel/alpha")
        val page = source.popular(1)
        assertEquals(1, page.books.size)
        assertEquals("Alpha Tale", page.books[0].title)
        val chapters = source.chapters(page.books[0])
        assertEquals(3, chapters.size)
    }

    @Test
    fun `live spec round trips`() {
        val spec = LiveSourceSpec(
            id = "live.x-example", name = "X", baseUrl = "https://x.example",
            startUrl = "https://x.example/novel/a", iconUrl = "https://x.example/i.png"
        )
        assertEquals(spec, LiveSourceSpec.decode(spec.encode()))
    }

    // ── 5. seeds ────────────────────────────────────────────

    @Test
    fun `seed catalogue names known hosts`() {
        assertEquals("WTR-LAB", SeedCatalog.nameFor("www.wtr-lab.com"))
        assertEquals("WTR-LAB", SeedCatalog.nameFor("https://wtr-lab.com/novel/x"))
        assertEquals("Royal Road", SeedCatalog.nameFor("royalroad.com"))
        assertNull(SeedCatalog.nameFor("totally-unknown.example"))
        assertTrue(SeedCatalog.isKnown("novelfull.com"))
        assertFalse(SeedCatalog.isKnown("totally-unknown.example"))
    }
}
