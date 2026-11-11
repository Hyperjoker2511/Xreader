package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.prefs.PerfTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.9 (Finding 2 §5.2): the rendered fetcher's
 * allowed-hosts guard NAMES the judged host in its refusal
 * detail — "that page is on <host>, outside this source's
 * allowed sites" — at BOTH guard sites (fetchRendered and
 * fetchInContext), so the book page's error card and Copy
 * details convict the mechanism unaided.
 *
 * Seam used: the REAL [WebViewRenderedFetcher] under
 * Robolectric. Both guards judge the request URL and refuse
 * BEFORE any WebView is created, any session is loaded or
 * any navigation happens, so the production guard code
 * itself runs here end-to-end (context = the Robolectric
 * application; the fetcher's constructor only registers
 * component callbacks). The factored construction
 * ([renderedGuardDetail]) is pinned directly as well.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class V159RenderedGuardDetailTest {

    private val permissions =
        ExtensionPermissions(domains = listOf("brightnovels.com"))
    private val foreignUrl =
        "https://brightnovel.com/series/the-dullard"
    private val expectedDetail =
        "that page is on brightnovel.com, outside this " +
            "source's allowed sites"

    private fun fetcher(): WebViewRenderedFetcher =
        WebViewRenderedFetcher(
            RuntimeEnvironment.getApplication(),
            SessionStore(testVault()),
            { PerfTier.FULL }
        )

    @Test
    fun `detail construction names the judged host verbatim`() {
        assertEquals(
            expectedDetail, renderedGuardDetail("brightnovel.com")
        )
        assertTrue(
            renderedGuardDetail("brightnovel.com")
                .contains("brightnovel.com")
        )
    }

    @Test
    fun `fetchRendered guard refusal names the judged host`() {
        runBlocking {
            val result = fetcher().fetchRendered(
                foreignUrl, "guard-test", permissions, null
            )
            val error = (result as? AppResult.Err)?.error
                ?: throw AssertionError(
                    "expected the guard to refuse, got $result"
                )
            assertEquals(expectedDetail, error.detail)
        }
    }

    @Test
    fun `fetchInContext guard refusal names the judged host`() {
        runBlocking {
            val result = fetcher().fetchInContext(
                url = foreignUrl,
                parkUrl = "https://brightnovels.com/series",
                sessionKey = null,
                permissions = permissions
            )
            val error = (result as? AppResult.Err)?.error
                ?: throw AssertionError(
                    "expected the guard to refuse, got $result"
                )
            assertEquals(expectedDetail, error.detail)
        }
    }
}
