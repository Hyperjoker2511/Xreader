package app.xreader.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.3 (round-16 amendment B): a [StaticHeaderBinding]
 * learned over https must NOT apply to the http variant of
 * the same host — auth-shaped headers never leak to an
 * unencrypted fallback. Scheme-less legacy bindings keep
 * the historical host-only behaviour on both schemes, and
 * the synthesizer pins the harvest's scheme into every
 * binding it promotes.
 */
class V153AmendmentBSchemePinTest {

    private val pinnedHttps = StaticHeaderBinding(
        headerName = "X-Api-Key",
        value = "secret-value",
        hosts = listOf("api.example.com"),
        scheme = "https"
    )

    @Test
    fun `an https binding matches https and not http on the same host`() {
        assertTrue(
            pinnedHttps.appliesTo("api.example.com", "https")
        )
        assertFalse(
            "https-learned header must not leak to http",
            pinnedHttps.appliesTo("api.example.com", "http")
        )
    }

    @Test
    fun `a pinned binding fails closed on an unknown scheme and a wrong host`() {
        assertFalse(pinnedHttps.appliesTo("api.example.com", null))
        assertFalse(pinnedHttps.appliesTo("other.example.com", "https"))
        // Scheme comparison is case-insensitive.
        assertTrue(pinnedHttps.appliesTo("api.example.com", "HTTPS"))
    }

    @Test
    fun `a scheme-less legacy binding keeps host-only behaviour`() {
        val legacy = StaticHeaderBinding(
            headerName = "X-Lang",
            value = "en_US",
            hosts = listOf("api.example.com")
        )
        assertTrue(legacy.appliesTo("api.example.com", "https"))
        assertTrue(legacy.appliesTo("api.example.com", "http"))
        assertTrue(legacy.appliesTo("api.example.com"))
        assertFalse(legacy.appliesTo("other.example.com", "https"))
    }

    @Test
    fun `the synthesizer pins the harvest scheme into promoted bindings`() {
        fun call(
            url: String,
            body: String,
            headers: Map<String, String>
        ) = HarvestedCall(
            url = url,
            method = "GET",
            status = 200,
            contentType = "application/json",
            requestHeaders = headers,
            bodySample = body
        )
        val listing = """[
          {"title":"Wave One","slug":"wave-one"},
          {"title":"Wave Two","slug":"wave-two"},
          {"title":"Wave Three","slug":"wave-three"}
        ]""".trimIndent()
        val headers = mapOf("X-Lang" to "en_US")
        val calls = listOf(
            call(
                "https://api.stories.example/titles/search?q=wave",
                listing,
                headers
            ),
            call(
                "https://api.stories.example/titles/wave-one",
                """{"title":"Wave One","slug":"wave-one"}""",
                headers
            ),
            call(
                "https://api.stories.example/titles/wave-one/chapters",
                """{"chapters":[
                  {"title":"Chapter 1","id":"c1"},
                  {"title":"Chapter 2","id":"c2"}
                ]}""",
                headers
            )
        )
        val synthesis = ApiRecipeSynthesizer.synthesize(
            calls,
            webBaseUrl = "https://stories.example",
            siteName = "Stories Shape",
            bookUrlHint = "https://stories.example/titles/wave-one"
        )
        assertNotNull("synthesis must succeed", synthesis)
        val promoted = synthesis!!.config.credentials?.staticHeaders
            .orEmpty()
        assertEquals(1, promoted.size)
        val binding = promoted.single()
        assertEquals("https", binding.scheme)
        assertTrue(
            binding.appliesTo("api.stories.example", "https")
        )
        assertFalse(
            binding.appliesTo("api.stories.example", "http")
        )
    }
}
