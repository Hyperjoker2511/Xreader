package app.xreader.data.extension

import app.xreader.core.error.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit parser rules (v1.0.4, D14): the field mapping round-trips a
 * real config untouched, and the candidate builder refuses —
 * plainly, before any test-drive is spent — the edits that would
 * leave a source unable to read.
 */
class ParserRulesEditorTest {

    @Test
    fun `untouched fields rebuild the identical config`() {
        val config = FixtureSite.config()
        val built = ParserRulesEditor.buildCandidate(
            config, ParserRulesEditor.fieldsOf(config)
        )
        assertTrue("expected Ok, got $built", built is AppResult.Ok)
        assertEquals(
            config,
            (built as AppResult.Ok).value
        )
    }

    @Test
    fun `an edit lands and untouched parts are carried through`() {
        val config = FixtureSite.config()
        val fields = ParserRulesEditor.fieldsOf(config).copy(
            detailsDescription = ".synopsis || meta[name=description]@content",
            chapterReverse = false
        )
        val built = ParserRulesEditor.buildCandidate(config, fields)
        assertTrue(built is AppResult.Ok)
        val candidate = (built as AppResult.Ok).value
        assertEquals(
            ".synopsis || meta[name=description]@content",
            candidate.details.description
        )
        assertEquals(false, candidate.details.chapters.reverse)
        // Carried through untouched:
        assertEquals(config.search, candidate.search)
        assertEquals(config.chapter.content, candidate.chapter.content)
        assertEquals(config.sampleBookUrl, candidate.sampleBookUrl)
        assertEquals(config.id, candidate.id)
    }

    @Test
    fun `blank chapter list rules are refused`() {
        val config = FixtureSite.config()
        val fields = ParserRulesEditor.fieldsOf(config).copy(chapterList = " ")
        val built = ParserRulesEditor.buildCandidate(config, fields)
        assertTrue(built is AppResult.Err)
        val reason = ((built as AppResult.Err).error as
            app.xreader.core.error.AppError.BadFile).reason
        assertTrue(reason.contains("chapter list"))
    }

    @Test
    fun `blank chapter text rule is refused`() {
        val config = FixtureSite.config()
        val fields = ParserRulesEditor.fieldsOf(config)
            .copy(contentSelector = "")
        val built = ParserRulesEditor.buildCandidate(config, fields)
        assertTrue(built is AppResult.Err)
    }

    @Test
    fun `an existing listing cannot be cleared away`() {
        val config = FixtureSite.config()
        val fields = ParserRulesEditor.fieldsOf(config).copy(
            search = ParserRulesEditor.ListingFields()
        )
        val built = ParserRulesEditor.buildCandidate(config, fields)
        assertTrue(built is AppResult.Err)
        val reason = ((built as AppResult.Err).error as
            app.xreader.core.error.AppError.BadFile).reason
        assertTrue(reason.contains("Search"))
    }

    @Test
    fun `search can be added to a source that has none`() {
        val config = FixtureSite.config().copy(search = null)
        val fields = ParserRulesEditor.fieldsOf(config).copy(
            search = ParserRulesEditor.ListingFields(
                url = "https://novels.example/search?q={query}",
                results = "div.result",
                title = "a.t",
                link = "a.t@href"
            )
        )
        val built = ParserRulesEditor.buildCandidate(config, fields)
        assertTrue("expected Ok, got $built", built is AppResult.Ok)
        val candidate = (built as AppResult.Ok).value
        assertNotNull(candidate.search)
        assertEquals("div.result", candidate.search!!.results)
    }

    @Test
    fun `half filled new listing is refused`() {
        val config = FixtureSite.config().copy(search = null)
        val fields = ParserRulesEditor.fieldsOf(config).copy(
            search = ParserRulesEditor.ListingFields(
                url = "https://novels.example/search?q={query}"
            )
        )
        val built = ParserRulesEditor.buildCandidate(config, fields)
        assertTrue(built is AppResult.Err)
    }

    @Test
    fun `blank optional book fields become null again`() {
        val config = FixtureSite.config()
        val fields = ParserRulesEditor.fieldsOf(config)
            .copy(detailsDescription = "")
        val built = ParserRulesEditor.buildCandidate(config, fields)
        assertTrue(built is AppResult.Ok)
        assertNull((built as AppResult.Ok).value.details.description)
    }

    @Test
    fun `remove rules round trip one per line`() {
        val config = FixtureSite.config()
        val fields = ParserRulesEditor.fieldsOf(config)
        assertEquals("div.ad", fields.contentRemove)
        val fields2 = fields.copy(contentRemove = "div.ad\n\n span.promo \n")
        val built = ParserRulesEditor.buildCandidate(config, fields2)
        assertTrue(built is AppResult.Ok)
        assertEquals(
            listOf("div.ad", "span.promo"),
            (built as AppResult.Ok).value.chapter.remove
        )
    }
}
