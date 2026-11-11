package app.xreader.data.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (K4): renderCarriesHint counts a matched node as
 * carrying content when an attribute NAMED IN THE SELECTOR
 * ITSELF is present and non-blank on it (BrightNovel's payload
 * rides `data-page`). Selector-named attributes ONLY.
 */
class V158AttributeCarriesHintTest {

    private val selector = "div#app[data-page]"
    private val url = "https://brightnovel.example/series/x"

    @Test
    fun `non-blank selector-named attribute with no text carries`() {
        val body = """<html><body><div id="app" data-page="{&quot;props&quot;:{}}"></div></body></html>"""
        assertTrue(renderCarriesHintCheck(body, url, selector))
    }

    @Test
    fun `blank or absent selector-named attribute does not carry`() {
        val blank = """<html><body><div id="app" data-page=""></div></body></html>"""
        assertFalse(renderCarriesHintCheck(blank, url, selector))
        val absent = """<html><body><div id="app"></div></body></html>"""
        assertFalse(renderCarriesHintCheck(absent, url, selector))
    }

    @Test
    fun `an attribute NOT named in the selector does not carry`() {
        // data-page present, but the selector names nothing —
        // no general attribute acceptance.
        val body = """<html><body><div id="app" data-page="{x}"></div></body></html>"""
        assertFalse(renderCarriesHintCheck(body, url, "div#app"))
    }

    @Test
    fun `the classic carriers still carry`() {
        val text = """<html><body><div id="app" data-page="">Chapter one text</div></body></html>"""
        assertTrue(renderCarriesHintCheck(text, url, selector))
    }
}
