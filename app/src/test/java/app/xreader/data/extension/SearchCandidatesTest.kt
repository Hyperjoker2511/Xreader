package app.xreader.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.0.1 hotfix: the search conventions tried when a site has no
 * discoverable search form — encoding (form-style, CJK included),
 * the documented candidate order, and GET-vs-POST form attempts.
 */
class SearchCandidatesTest {

    // ── encodeQuery ───────────────────────────────────────────────

    @Test
    fun `spaces encode as plus in form style`() {
        assertEquals("star+fall+rising", SearchCandidates.encodeQuery("star fall rising"))
        assertEquals("", SearchCandidates.encodeQuery(""))
    }

    @Test
    fun `reserved characters are percent-encoded`() {
        assertEquals("a+%26+b%3Dc", SearchCandidates.encodeQuery("a & b=c"))
        assertEquals("100%25", SearchCandidates.encodeQuery("100%"))
    }

    @Test
    fun `CJK is UTF-8 percent-encoded never raw`() {
        // 星 = E6 98 9F, 尘 = E5 B0 98 in UTF-8.
        assertEquals("%E6%98%9F%E5%B0%98", SearchCandidates.encodeQuery("星尘"))
        assertEquals(
            "%E6%98%9F%E5%B0%98+tales",
            SearchCandidates.encodeQuery("星尘 tales")
        )
    }

    @Test
    fun `URLEncoder quirks are kept`() {
        // URLEncoder leaves `*` (and `. - _`) raw but encodes `~`.
        assertEquals("a*b.c-d_e", SearchCandidates.encodeQuery("a*b.c-d_e"))
        assertEquals("a%7Eb", SearchCandidates.encodeQuery("a~b"))
    }

    // ── conventionUrls ────────────────────────────────────────────

    @Test
    fun `conventions come in the documented order`() {
        val urls = SearchCandidates.conventionUrls("https://site.example", "star fall")
        assertEquals(
            listOf(
                "https://site.example/search?q=star+fall",
                "https://site.example/search?keyword=star+fall",
                "https://site.example/search/star%20fall",
                "https://site.example/?s=star+fall",
                "https://site.example/search.html?wd=star+fall",
                "https://site.example/search.html?keyword=star+fall"
            ),
            urls
        )
    }

    @Test
    fun `trailing slash on the base is normalised away`() {
        assertEquals(
            SearchCandidates.conventionUrls("https://site.example", "x"),
            SearchCandidates.conventionUrls("https://site.example/", "x")
        )
        assertEquals(
            SearchCandidates.conventionUrls("https://site.example", "x"),
            SearchCandidates.conventionUrls(" https://site.example// ", "x")
        )
    }

    @Test
    fun `path form uses percent-20 for spaces and encodes slashes`() {
        val pathUrl = SearchCandidates.conventionUrls("https://site.example", "a/b c")[2]
        assertEquals("https://site.example/search/a%2Fb%20c", pathUrl)
    }

    @Test
    fun `blank base yields no candidates`() {
        assertEquals(emptyList<String>(), SearchCandidates.conventionUrls("", "x"))
        assertEquals(emptyList<String>(), SearchCandidates.conventionUrls("  ", "x"))
    }

    // ── formAttempt ───────────────────────────────────────────────

    @Test
    fun `GET attempt appends the pair to the action`() {
        val attempt = SearchCandidates.formAttempt(
            formAction = "https://site.example/search",
            inputName = "q",
            method = "get",
            query = "star fall",
            baseUrl = "https://site.example"
        )
        assertEquals("https://site.example/search?q=star+fall", attempt.url)
        assertEquals("GET", attempt.method)
        assertNull(attempt.body)
    }

    @Test
    fun `GET attempt uses ampersand when the action already has a query`() {
        val attempt = SearchCandidates.formAttempt(
            formAction = "https://site.example/search?lang=en",
            inputName = "keyword",
            method = "GET",
            query = "moon",
            baseUrl = "https://site.example"
        )
        assertEquals("https://site.example/search?lang=en&keyword=moon", attempt.url)
        assertNull(attempt.body)
    }

    @Test
    fun `POST attempt puts the pair in the body only`() {
        val attempt = SearchCandidates.formAttempt(
            formAction = "https://site.example/search",
            inputName = "keyword",
            method = "post",
            query = "star fall",
            baseUrl = "https://site.example"
        )
        assertEquals("https://site.example/search", attempt.url)
        assertEquals("POST", attempt.method)
        assertEquals("keyword=star+fall", attempt.body)
    }

    @Test
    fun `path-only action resolves against the base`() {
        val get = SearchCandidates.formAttempt(
            formAction = "/search",
            inputName = "q",
            method = "GET",
            query = "moon",
            baseUrl = "https://site.example"
        )
        assertEquals("https://site.example/search?q=moon", get.url)

        val post = SearchCandidates.formAttempt(
            formAction = "/find",
            inputName = "wd",
            method = "POST",
            query = "moon",
            baseUrl = "https://site.example/novels/"
        )
        assertEquals("https://site.example/find", post.url)
        assertEquals("wd=moon", post.body)
    }

    @Test
    fun `relative action resolves against the base path`() {
        val attempt = SearchCandidates.formAttempt(
            formAction = "search",
            inputName = "q",
            method = "GET",
            query = "moon",
            baseUrl = "https://site.example/novels/"
        )
        assertEquals("https://site.example/novels/search?q=moon", attempt.url)
    }

    @Test
    fun `unknown methods fall back to GET and blank names to q`() {
        val attempt = SearchCandidates.formAttempt(
            formAction = "https://site.example/search",
            inputName = " ",
            method = "dialog",
            query = "moon",
            baseUrl = "https://site.example"
        )
        assertEquals("GET", attempt.method)
        assertEquals("https://site.example/search?q=moon", attempt.url)
        assertNull(attempt.body)
    }
}
