package app.xreader.ui.sources

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.xreader.data.extension.CookieParsing
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.PrimaryPill
import java.net.URI

/**
 * v1.3.4 (U2): the sanctioned screen's invariants, as pure
 * constants a JVM test can pin.
 *
 * Check widgets (Turnstile & friends) anchor near the
 * viewport bottom, and the phone's screen cover overlaps
 * the lowest sliver of the display — so the web area must
 * always be able to scroll its widget into view, and the
 * viewport's bottom edge must sit well clear of the bottom
 * edge: the confirm bar already lifts ~88dp off the
 * screen's bottom edge, and the web area carries ~40dp
 * more of bottom inset on top of that (total lift
 * ≥ ~128dp).
 */
object SanctionedScreenSpec {
    /** Extra bottom inset inside the web area, in dp. */
    const val WEB_AREA_BOTTOM_INSET_DP = 40

    /** The confirm bar's existing lift off the screen's
     * bottom edge (12dp column padding + 76dp spacer), in dp. */
    const val CONFIRM_BAR_BOTTOM_LIFT_DP = 88

    /** The web area must scroll — a check widget anchored
     * low on the page is only reachable if it can be
     * scrolled into view. Always true. */
    const val REQUIRES_SCROLL = true

    /** The sanctioned WebView never needs local files or
     * content-provider data to show a site's sign-in/check page. */
    const val ALLOW_FILE_ACCESS = false

    /** See [ALLOW_FILE_ACCESS]. */
    const val ALLOW_CONTENT_ACCESS = false

    /** Mixed HTTP/HTTPS content is never allowed on the
     * sanctioned screen (WebSettings.MIXED_CONTENT_NEVER_ALLOW). */
    const val MIXED_CONTENT_NEVER_ALLOW = true

    /** Total bottom lift for content at the web viewport's
     * bottom edge, in dp. */
    fun totalBottomLiftDp(): Int =
        CONFIRM_BAR_BOTTOM_LIFT_DP + WEB_AREA_BOTTOM_INSET_DP
}

/**
 * The assisted login (PLAN §9.3): a plain built-in browser where the
 * USER signs in and passes any checks himself — there is deliberately
 * no automation here, no CAPTCHA solving, nothing that pretends to be
 * the user. When he taps "I'm signed in", the site's session cookies
 * for this source's host are captured into the KeyVault and reused by
 * that source's requests. The password is typed into the site's own
 * page and is never seen, stored, or logged by Xreader.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    request: LoginRequest,
    viewModel: SourcesViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    // v1.5.2 fix (FIX-2): the widened capture set for this source,
    // resolved once per request (request-carried hosts win; the
    // view model's config lookup fills the rest).
    var configuredHostsNow by remember(request.sessionKey) {
        mutableStateOf(request.configuredHosts)
    }
    androidx.compose.runtime.LaunchedEffect(request.sessionKey) {
        if (configuredHostsNow.isEmpty()) {
            configuredHostsNow = runCatching {
                viewModel.configuredHosts(request.sessionKey)
            }.getOrDefault(emptyList())
        }
    }
    // v1.3.4 (U2/U3): open the failure's own page when one was
    // named (e.g. the source's reader page, where its check
    // widget lives); the site root otherwise, as before. The
    // UA is whatever this WebView already uses — no per-screen
    // override is introduced here, so the flow's UA stays
    // consistent start to finish.
    val effectiveUrl = request.targetUrl ?: request.url
    val webView = remember(request.sessionKey, effectiveUrl) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = SanctionedScreenSpec.ALLOW_FILE_ACCESS
            settings.allowContentAccess =
                SanctionedScreenSpec.ALLOW_CONTENT_ACCESS
            if (SanctionedScreenSpec.MIXED_CONTENT_NEVER_ALLOW) {
                settings.mixedContentMode =
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            // The page must be able to scroll (SanctionedScreenSpec):
            // scrolling stays enabled so a low-anchored check
            // widget can always be brought into view.
            isVerticalScrollBarEnabled = true
            // v1.5.5 (§3.3): the read-token harvest hook rides
            // the sanctioned page — when the site's own reader
            // page fires its /api/read-token mint DURING the
            // solve, the hook clones the answer into the
            // process sink, and the post-save chapter fetch
            // adopts that token instead of minting twice. The
            // bridge is the same app-owned, results-only
            // interface the rendered fetcher uses; the hook is
            // injected at page start (the old-WebView fallback
            // for document-start injection).
            val tokenBridge =
                app.xreader.data.extension.InContextBridge.Bridge()
            addJavascriptInterface(
                tokenBridge,
                app.xreader.data.extension.InContextBridge.INTERFACE_NAME
            )
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    view?.evaluateJavascript(
                        app.xreader.data.extension.InContextBridge
                            .tokenHarvestHookScript(),
                        null
                    )
                }
                // v1.5.1 (item 2, adopted steer): if the
                // renderer process dies under the sanctioned
                // screen, destroy the view and leave the
                // screen honestly (the user can reopen the
                // solve) — a dead renderer must never kill
                // the app.
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    runCatching { view?.destroy() }
                    onCancel()
                    return true
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl(effectiveUrl)
        }
    }
    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    // v1.0.6 (W4.2): SITE_CHECK mode — the same
                    // screen, the words swapped. This screen is the
                    // ONLY place a browser ever becomes visible.
                    if (request.mode == LoginMode.SITE_CHECK) {
                        "One quick check — ${request.title}"
                    } else {
                        "Sign in to ${request.title}"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    effectiveUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // v1.3.4 (U2): the web area carries the spec's bottom
        // inset INSIDE its own bounds — the viewport's bottom
        // edge (where check widgets anchor) sits the inset
        // higher, on top of the confirm bar's own lift, so the
        // widget is always clear of the screen's bottom edge
        // and scrollable into view.
        AndroidView(
            factory = { webView },
            modifier = Modifier.weight(1f).fillMaxWidth()
                .padding(
                    bottom = SanctionedScreenSpec
                        .WEB_AREA_BOTTOM_INSET_DP.dp
                )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (request.mode == LoginMode.SITE_CHECK) {
                "This site wants a quick human check before it shows its pages. " +
                    "Do the check here; after that Xreader reads the site quietly " +
                    "in the background — you'll never see a browser while you read."
            } else {
                "Sign in as you normally would — including any “I'm not a robot” checks. " +
                    "Then tap the button below: Xreader saves this site's session cookies " +
                    "(encrypted, only for this source) and never sees your password."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // v1.5.8 (J10/C4): the honest echo — saving certifies
        // only that the session was SAVED. It does not claim
        // the check is finished: if the site is still cycling
        // its check, the words say so and name what happens
        // next, instead of letting "saved" imply "solved".
        Text(
            if (request.mode == LoginMode.SITE_CHECK) {
                "Saving keeps this session — it doesn't mean the check " +
                    "is finished. If the site is still checking when you " +
                    "open a book, Xreader will say so and ask you to " +
                    "solve it once more here."
            } else {
                "Saving keeps only this session. If the site asks for " +
                    "another check later, Xreader will say so plainly " +
                    "when you open a book."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        // §5.9 chrome only: the bottom confirm bar is a PrimaryPill,
        // lifted clear of the screen's bottom edge by the spacer
        // below. The WebView above is untouched.
        PrimaryPill(
            text = if (request.mode == LoginMode.SITE_CHECK) {
                "Done — continue"
            } else {
                "I'm signed in — save my session"
            },
            onClick = {
                val cookieManager = CookieManager.getInstance()
                // v1.5.0 (steer A4): flush before reading —
                // the jar's newest cookies (the solve's own)
                // must be durable before they are harvested
                // into the session store.
                cookieManager.flush()
                val currentUrl = webView.url ?: effectiveUrl
                val fallbackHost = CookieParsing.hostOf(currentUrl)
                    ?: CookieParsing.hostOf(request.url) ?: ""
                // v1.3.5 (P0): capture PER ORIGIN — the cookies
                // the jar returns for each candidate are saved
                // under THAT candidate's host, never merged into
                // one map under a single host (a sign-in that
                // crossed origins used to smear every origin's
                // cookies onto the final page's host).
                val cookiesByHost = loginCookiesByOrigin(
                    currentUrl = currentUrl,
                    originalUrl = effectiveUrl,
                    cookieHeaderFor = { candidate ->
                        cookieManager.getCookie(candidate)
                    },
                    configuredHosts = configuredHostsNow
                )
                viewModel.saveLoginSession(
                    sessionKey = request.sessionKey,
                    cookiesByHost = cookiesByHost,
                    fallbackHost = fallbackHost,
                    userAgent = webView.settings.userAgentString
                )
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        )
        GhostPill(
            text = "Cancel",
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        )
        // 12dp column padding + this spacer = the bottom action cluster sits
        // 88dp clear of the screen's bottom edge (deep bottom
        // padding — the J10 fence, with the web area's own
        // scroll above it).
        Spacer(Modifier.height(76.dp))
    }
}

/** URLs to ask the cookie jar about: the page itself, its origin, and
 * the original login URL's origin. */
private fun cookieCandidateUrls(
    currentUrl: String, originalUrl: String,
    configuredHosts: List<String> = emptyList()
): List<String> {
    val urls = LinkedHashSet<String>()
    urls += currentUrl
    urls += originalUrl
    for (url in listOf(currentUrl, originalUrl)) {
        runCatching {
            val uri = URI(url)
            urls += "${uri.scheme}://${uri.host}/"
        }
    }
    // v1.5.2 fix (FIX-2, §1 residue a): the candidate set is WIDENED
    // to the source's configured hosts — a session whose cookies
    // live on a configured host the login page never visited is
    // captured too.
    for (host in configuredHosts) {
        urls += "https://$host/"
    }
    return urls.toList()
}

/**
 * v1.3.5 (P0): the login capture, grouped by origin — for every
 * candidate URL, the cookies the jar would send THERE are filed
 * under that URL's own host. Candidates on the same host merge
 * into that host's entry; different hosts never mix. Pure (the
 * jar read is the caller's lambda) so the grouping is testable
 * without a WebView.
 */
internal fun loginCookiesByOrigin(
    currentUrl: String,
    originalUrl: String,
    cookieHeaderFor: (String) -> String?,
    configuredHosts: List<String> = emptyList()
): Map<String, Map<String, String>> {
    val byHost = LinkedHashMap<String, LinkedHashMap<String, String>>()
    for (candidate in cookieCandidateUrls(currentUrl, originalUrl, configuredHosts)) {
        val host = CookieParsing.hostOf(candidate) ?: continue
        val header = cookieHeaderFor(candidate) ?: continue
        val parsed = CookieParsing.parseCookieHeader(header)
        if (parsed.isEmpty()) continue
        byHost.getOrPut(host) { LinkedHashMap() }.putAll(parsed)
    }
    return byHost
}
