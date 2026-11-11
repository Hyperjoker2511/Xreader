package app.xreader.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.3 (round-16 amendment A): a temporal header must
 * NEVER be promoted to a static replay header by the
 * synthesizer's promotion pass — not when its NAME is a
 * known temporal name, and not when its VALUE is
 * timestamp-shaped (epoch seconds/millis, ISO-8601,
 * RFC-1123), even when the value is identical on every
 * harvested call (a fast harvest burst captures identical
 * stale timestamps; replaying them earns 403s on strict
 * APIs). Ordinary constant headers on the same calls must
 * still promote, exactly as before.
 */
class V153AmendmentAReplayTemporalTest {

    private fun call(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ) = HarvestedCall(
        url = url,
        method = "GET",
        status = 200,
        contentType = "application/json",
        requestHeaders = headers,
        bodySample = body
    )

    /** Every API call of the harvest carries the same
     * header set: three temporal headers (one per exclusion
     * route) and two ordinary constants. */
    private fun harvestHeaders(): Map<String, String> = mapOf(
        // Temporal NAME (and an epoch-seconds value).
        "X-Client-Timestamp" to "1730467200",
        // Temporal NAME whose value is not timestamp-shaped.
        "X-Timestamp" to "burst-7f3a",
        // Neutral name, epoch-MILLIS value.
        "X-Trace-Id" to "1730467200123",
        // Neutral name, ISO-8601 value.
        "X-Build-Stamp" to "2026-11-04T03:30:00Z",
        // Neutral name, RFC-1123 value.
        "X-Build-Date" to "Wed, 04 Nov 2026 03:30:00 GMT",
        // Ordinary constants — must still promote.
        "X-Lang" to "en_US",
        "X-Site-Domain" to "stories.example"
    )

    @Test
    fun `temporal headers never promote, ordinary constants still do`() {
        val listing = """[
          {"title":"Wave One","slug":"wave-one"},
          {"title":"Wave Two","slug":"wave-two"},
          {"title":"Wave Three","slug":"wave-three"}
        ]""".trimIndent()
        val headers = harvestHeaders()
        val calls = listOf(
            call(
                "https://api.stories.example/titles/search?q=wave",
                listing,
                headers = headers
            ),
            call(
                "https://api.stories.example/titles/wave-one",
                """{"title":"Wave One","slug":"wave-one"}""",
                headers = headers
            ),
            call(
                "https://api.stories.example/titles/wave-one/chapters",
                """{"chapters":[
                  {"title":"Chapter 1","id":"c1"},
                  {"title":"Chapter 2","id":"c2"}
                ]}""",
                headers = headers
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
        val promotedNames = promoted.map { it.headerName }
            .map { it.lowercase() }
            .toSet()

        // The ordinary constants promote, with their values.
        assertTrue(
            "ordinary constant X-Lang must still promote",
            "x-lang" in promotedNames
        )
        assertTrue(
            "ordinary constant X-Site-Domain must still promote",
            "x-site-domain" in promotedNames
        )
        assertEquals(
            "en_US",
            promoted.first {
                it.headerName.equals("X-Lang", ignoreCase = true)
            }.value
        )

        // No temporal header promotes, by any route.
        for (temporal in listOf(
            "x-client-timestamp", "x-timestamp", "x-trace-id",
            "x-build-stamp", "x-build-date"
        )) {
            assertTrue(
                "temporal header $temporal must never promote",
                temporal !in promotedNames
            )
        }
        assertEquals(
            setOf("x-lang", "x-site-domain"),
            promotedNames
        )
    }
}
