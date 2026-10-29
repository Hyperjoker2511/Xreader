package app.xreader.data.extension

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Legado conversion (v1.0.1 hotfix): pure-selector sources convert
 * to a DeclarativeConfig with the Legado-isms cleaned (the `@css:`
 * prefix, the `,{…}` search suffix, `{{key}}` → `{query}`, and
 * `##regex##replacement` suffixes kept as cleaner rules); a source
 * whose rules run script code is refused outright.
 */
class LegadoConverterTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val twoSourceFile = """
        [
          {
            "bookSourceName": "Pure Novels",
            "bookSourceUrl": "https://pure.example",
            "bookSourceGroup": "test",
            "searchUrl": "https://pure.example/search?key={{key}}&page={{page}},{\"method\":\"GET\"}",
            "ruleSearch": {
              "bookList": "@css:.book-list .item",
              "name": ".title@text",
              "author": ".author@text",
              "bookUrl": "a@href",
              "coverUrl": "img@src"
            },
            "ruleBookInfo": {
              "name": "h1.title@text",
              "author": ".info .author@text",
              "intro": ".intro@text##ADVERT##",
              "coverUrl": "img.cover@src"
            },
            "ruleToc": {
              "chapterList": ".chapter-list li",
              "chapterName": "a@text",
              "chapterUrl": "a@href"
            },
            "ruleContent": {
              "content": "div.chapter-content"
            }
          },
          {
            "bookSourceName": "Script Novels",
            "bookSourceUrl": "https://script.example",
            "searchUrl": "https://script.example/search?key={{key}}",
            "ruleSearch": {
              "bookList": ".results .item",
              "name": ".title@text",
              "bookUrl": "a@href"
            },
            "ruleToc": {
              "chapterList": ".chapters li",
              "chapterName": "a@text",
              "chapterUrl": "a@href"
            },
            "ruleContent": {
              "content": "div.content<js>java.ajax('https://script.example/x')</js>"
            }
          }
        ]
    """.trimIndent()

    @Test
    fun `parseLegadoFile lists both sources with names and urls`() {
        val sources = LegadoConverter.parseLegadoFile(twoSourceFile)
        assertEquals(2, sources.size)
        assertEquals("Pure Novels", sources[0].name)
        assertEquals("https://pure.example", sources[0].url)
        assertEquals("Script Novels", sources[1].name)
        assertEquals("https://script.example", sources[1].url)
        assertTrue(LegadoConverter.parseLegadoFile("not json").isEmpty())
        assertTrue(LegadoConverter.parseLegadoFile("").isEmpty())
    }

    @Test
    fun `pure selector source converts with mapped rules`() {
        val source = LegadoConverter.parseLegadoFile(twoSourceFile).first()
        val result = LegadoConverter.convert(source)
        assertTrue(result is LegadoConversionResult.Converted)
        val config = (result as LegadoConversionResult.Converted).config

        assertEquals("Pure Novels", config.name)
        assertEquals("https://pure.example", config.baseUrl)
        assertTrue(config.id.isNotBlank())
        assertTrue(config.id.startsWith("legado-"))

        // Search: ,{…} suffix stripped, {{key}} → {query},
        // {{page}} → {page}, @css: prefix stripped.
        val search = config.search
        assertNotNull(search)
        assertEquals(
            "https://pure.example/search?key={query}&page={page}",
            search!!.url
        )
        assertEquals(".book-list .item", search.results)
        assertEquals(".title@text", search.title)
        assertEquals("a@href", search.link)
        assertEquals("img@src", search.cover)
        assertEquals(".author@text", search.author)

        // Book page + chapter list.
        assertEquals("h1.title@text", config.details.title)
        assertEquals(".info .author@text", config.details.author)
        assertEquals("img.cover@src", config.details.cover)
        assertEquals(".chapter-list li", config.details.chapters.list)
        assertEquals("a@text", config.details.chapters.name)
        assertEquals("a@href", config.details.chapters.link)

        // The ## suffix left the selector and became a cleaner rule.
        assertEquals(".intro@text", config.details.description)
        assertEquals(1, config.cleaners.size)
        assertEquals("ADVERT", config.cleaners.first().pattern)
        assertEquals("", config.cleaners.first().replacement)
        val notes = (result as LegadoConversionResult.Converted).notes
        assertTrue(
            "the ## cleanup is recorded in the notes",
            notes.any { it.contains("ADVERT") }
        )

        // Chapter content.
        assertEquals("div.chapter-content", config.chapter.content)
    }

    @Test
    fun `source with a js rule is refused`() {
        val source = LegadoConverter.parseLegadoFile(twoSourceFile)[1]
        val result = LegadoConverter.convert(source)
        assertTrue(result is LegadoConversionResult.Refused)
        val reason = (result as LegadoConversionResult.Refused).reason
        assertTrue(reason.isNotBlank())
        assertTrue(reason.contains("ruleContent.content"))
    }

    @Test
    fun `source with an at-js rule is refused`() {
        val body = """
            {
              "bookSourceName": "AtJs Novels",
              "bookSourceUrl": "https://atjs.example",
              "ruleSearch": {"bookList": "@js:result.toString()", "name": ".t"},
              "ruleToc": {"chapterList": "li", "chapterName": "a", "chapterUrl": "a@href"},
              "ruleContent": {"content": "div.content"}
            }
        """.trimIndent()
        val obj = json.parseToJsonElement(body).jsonObject
        val result = LegadoConverter.convert(obj as JsonObject)
        assertTrue(result is LegadoConversionResult.Refused)
    }

    @Test
    fun `search url angle suffix is stripped and keyword placeholder mapped`() {
        val body = """
            {
              "bookSourceName": "Angle Novels",
              "bookSourceUrl": "https://angle.example",
              "searchUrl": "https://angle.example/find/{{keyword}}<,POST,{\"body\":\"x\"}>",
              "ruleSearch": {"bookList": ".item", "name": ".t@text", "bookUrl": "a@href"},
              "ruleToc": {"chapterList": "li", "chapterName": "a@text", "chapterUrl": "a@href"},
              "ruleContent": {"content": "#content"}
            }
        """.trimIndent()
        val sources = LegadoConverter.parseLegadoFile(body)
        assertEquals(1, sources.size)
        val result = LegadoConverter.convert(sources.first())
        assertTrue(result is LegadoConversionResult.Converted)
        val config = (result as LegadoConversionResult.Converted).config
        assertEquals("https://angle.example/find/{query}", config.search?.url)
        assertEquals("#content", config.chapter.content)
    }

    @Test
    fun `convertAll reports per source in file order`() {
        val results = LegadoConverter.convertAll(twoSourceFile)
        assertEquals(2, results.size)
        assertTrue(results[0] is LegadoConversionResult.Converted)
        assertTrue(results[1] is LegadoConversionResult.Refused)
    }
}
