package app.xreader.ui.sources

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.xreader.data.extension.CookieParsing
import app.xreader.ui.common.XreaderPrimaryButton
import java.net.URI

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
    val webView = remember(request.sessionKey, request.url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl(request.url)
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
                    request.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AndroidView(
            factory = { webView },
            modifier = Modifier.weight(1f).fillMaxWidth()
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
        Spacer(Modifier.height(8.dp))
        XreaderPrimaryButton(
            text = if (request.mode == LoginMode.SITE_CHECK) {
                "Done — continue"
            } else {
                "I'm signed in — save my session"
            },
            onClick = {
                val cookieManager = CookieManager.getInstance()
                val currentUrl = webView.url ?: request.url
                val host = CookieParsing.hostOf(currentUrl)
                    ?: CookieParsing.hostOf(request.url) ?: ""
                val cookies = LinkedHashMap<String, String>()
                // Capture for the page's host and its parent domain —
                // sites scatter session cookies across both.
                for (candidate in cookieCandidateUrls(currentUrl, request.url)) {
                    cookieManager.getCookie(candidate)?.let { header ->
                        cookies.putAll(CookieParsing.parseCookieHeader(header))
                    }
                }
                viewModel.saveLoginSession(
                    sessionKey = request.sessionKey,
                    host = host,
                    cookies = cookies,
                    userAgent = webView.settings.userAgentString
                )
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

/** URLs to ask the cookie jar about: the page itself, its origin, and
 * the original login URL's origin. */
private fun cookieCandidateUrls(currentUrl: String, originalUrl: String): List<String> {
    val urls = LinkedHashSet<String>()
    urls += currentUrl
    urls += originalUrl
    for (url in listOf(currentUrl, originalUrl)) {
        runCatching {
            val uri = URI(url)
            urls += "${uri.scheme}://${uri.host}/"
        }
    }
    return urls.toList()
}
