package app.xreader.ui.sources

import app.xreader.data.extension.FetchClassification
import app.xreader.data.extension.SessionNeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.4 (U6): the UI package's pure invariants.
 *
 *  - The sanctioned screen's bottom lift: check widgets
 *    anchor near the viewport bottom and the user's phone
 *    has a dead bottom strip, so the web area's total lift
 *    must stay ≥ ~120dp and the page must scroll.
 *  - A login request carries its optional target page
 *    (the reader page for a check) and defaults to none.
 *  - The catalogue's copy decisions: availability/DNS lead
 *    with honest words and Retry; a session action never
 *    replaces the truth; an empty gated catalogue states
 *    the reason as UNKNOWN.
 */
class V134UiTest {

    // ── U2: sanctioned screen spec ──────────────────────────

    @Test
    fun `sanctioned screen total bottom lift clears the dead strip`() {
        assertTrue(
            "total lift was ${SanctionedScreenSpec.totalBottomLiftDp()}dp",
            SanctionedScreenSpec.totalBottomLiftDp() >= 120
        )
        assertEquals(
            SanctionedScreenSpec.CONFIRM_BAR_BOTTOM_LIFT_DP +
                SanctionedScreenSpec.WEB_AREA_BOTTOM_INSET_DP,
            SanctionedScreenSpec.totalBottomLiftDp()
        )
        assertTrue(
            SanctionedScreenSpec.WEB_AREA_BOTTOM_INSET_DP >= 40
        )
    }

    @Test
    fun `sanctioned screen requires scroll`() {
        assertTrue(SanctionedScreenSpec.REQUIRES_SCROLL)
    }

    @Test
    fun `sanctioned screen webview blocks local and mixed content`() {
        assertFalse(SanctionedScreenSpec.ALLOW_FILE_ACCESS)
        assertFalse(SanctionedScreenSpec.ALLOW_CONTENT_ACCESS)
        assertTrue(SanctionedScreenSpec.MIXED_CONTENT_NEVER_ALLOW)
    }

    // ── U2/U3: login request target ─────────────────────────

    @Test
    fun `login request defaults to no target page`() {
        val request = LoginRequest(
            sessionKey = "ext-1",
            url = "https://example.com",
            title = "Example"
        )
        assertNull(request.targetUrl)
        assertEquals(LoginMode.SIGN_IN, request.mode)
    }

    @Test
    fun `login request carries a provided target page`() {
        val request = LoginRequest(
            sessionKey = "ext-1",
            url = "https://example.com",
            title = "Example",
            mode = LoginMode.SITE_CHECK,
            targetUrl = "https://example.com/read/chapter-1"
        )
        assertEquals(
            "https://example.com/read/chapter-1", request.targetUrl
        )
        assertEquals("https://example.com", request.url)
    }

    // ── U4: catalogue copy decisions ────────────────────────

    private fun classification(
        kind: FetchClassification.Kind,
        retryAfterSeconds: Long? = null,
        rateLimited: Boolean = false
    ) = FetchClassification.Result(
        kind = kind,
        retryAfterSeconds = retryAfterSeconds,
        rateLimited = rateLimited
    )

    @Test
    fun `empty gated catalogue states the reason as unknown`() {
        for (need in SessionNeed.Kind.entries) {
            val message = CatalogueCopy.emptyMessage(
                need = need, isSearch = false
            )
            assertTrue(message.contains("Nothing came back"))
            assertTrue(message.contains("may be asking"))
            assertFalse(message.contains("likely"))
            assertFalse(
                message.lowercase().contains("wall")
            )
        }
    }

    @Test
    fun `empty ungated catalogue and search keep their own words`() {
        assertTrue(
            CatalogueCopy.emptyMessage(null, isSearch = false)
                .contains("came back empty")
        )
        assertTrue(
            CatalogueCopy.emptyMessage(null, isSearch = true)
                .contains("No matches")
        )
        // Search wording wins even on a gated source.
        assertTrue(
            CatalogueCopy.emptyMessage(
                SessionNeed.Kind.SITE_CHECK, isSearch = true
            ).contains("No matches")
        )
    }

    @Test
    fun `availability failure words split 503 from 429 with the wait`() {
        val unavailable = CatalogueCopy.failureMessage(
            classification(
                FetchClassification.Kind.TEMPORARILY_UNAVAILABLE
            )
        )!!
        assertTrue(unavailable.contains("temporarily unavailable"))
        assertFalse(unavailable.contains("too many requests"))

        val limited = CatalogueCopy.failureMessage(
            classification(
                FetchClassification.Kind.TEMPORARILY_UNAVAILABLE,
                retryAfterSeconds = 120,
                rateLimited = true
            )
        )!!
        assertTrue(
            limited.lowercase().contains("too many requests")
        )
        assertTrue(limited.contains("2 minutes"))
        // No raw codes anywhere in the honest words.
        for (words in listOf(unavailable, limited)) {
            assertFalse(words.contains("503"))
            assertFalse(words.contains("429"))
            assertFalse(words.contains("(error"))
        }
    }

    @Test
    fun `dns failure words use the plain marker and no raw code`() {
        val words = CatalogueCopy.failureMessage(
            classification(FetchClassification.Kind.DNS_FAILURE)
        )!!
        assertTrue(words.contains(FetchClassification.DNS_MARKER))
        assertFalse(words.contains("-2"))
    }

    @Test
    fun `ordinary failures carry no special catalogue words`() {
        assertNull(
            CatalogueCopy.failureMessage(
                classification(FetchClassification.Kind.GENERIC)
            )
        )
        assertNull(CatalogueCopy.failureMessage(null))
    }

    @Test
    fun `session action never replaces an availability truth`() {
        // Availability: no session action, even on a gated source.
        assertFalse(
            CatalogueCopy.offersSessionAction(
                classification(
                    FetchClassification.Kind.TEMPORARILY_UNAVAILABLE,
                    rateLimited = true
                ),
                SessionNeed.Kind.SITE_CHECK
            )
        )
        // DNS on a gated source: secondary action allowed.
        assertTrue(
            CatalogueCopy.offersSessionAction(
                classification(FetchClassification.Kind.DNS_FAILURE),
                SessionNeed.Kind.SITE_CHECK
            )
        )
        // DNS without a gate: nothing to solve.
        assertFalse(
            CatalogueCopy.offersSessionAction(
                classification(FetchClassification.Kind.DNS_FAILURE),
                null
            )
        )
        // A session-kind classification: the action is the point.
        assertTrue(
            CatalogueCopy.offersSessionAction(
                classification(
                    FetchClassification.Kind.SESSION_CHECK
                ),
                SessionNeed.Kind.SITE_CHECK
            )
        )
        assertTrue(
            CatalogueCopy.offersSessionAction(
                classification(FetchClassification.Kind.SIGN_IN),
                SessionNeed.Kind.SIGN_IN
            )
        )
    }

    @Test
    fun `session action labels are plain words`() {
        assertEquals(
            "Sign in",
            CatalogueCopy.sessionActionLabel(SessionNeed.Kind.SIGN_IN)
        )
        assertEquals(
            "Solve the check",
            CatalogueCopy.sessionActionLabel(
                SessionNeed.Kind.SITE_CHECK
            )
        )
        assertEquals(
            "Solve the check",
            CatalogueCopy.sessionActionLabel(null)
        )
    }
}
