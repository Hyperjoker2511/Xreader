package app.xreader.data.extension.player

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import okhttp3.OkHttpClient

/**
 * K2 (Keiyoushi player): the donor [NetworkHelper] wired to Xreader.
 *
 * Two substitutions, both at seams the donor leaves open:
 *
 * - The cookie jar is [SessionStoreCookieJar] (D2 rule: cookies resolve
 *   per-source-host from the encrypted SessionStore, never the global
 *   WebView jar). The base class's private client builder reads the
 *   open `cookieJar` property, so the base client — and every
 *   per-extension client a source derives from it the donor way
 *   (`network.client.newBuilder()` plus the source's own interceptors,
 *   which is exactly what HttpSource subclasses do) — carries session
 *   cookies with no further wiring.
 * - The Cloudflare path is our [CloudflareInterceptor] (sanctioned
 *   screen via [PlayerChallengeBridge]) instead of the donor's hidden
 *   WebView solve. It is added on top of the base client, so the
 *   interceptor order matches the donor's client shape: the donor's
 *   own interceptors (uncaught-exception, user-agent) run first, the
 *   Cloudflare interceptor last.
 *
 * Named deviation: the base's private download-with-resume path builds
 * its client straight from the base builder, so it carries the session
 * jar but not the Cloudflare interceptor. That path is host-internal
 * (NetworkHelper.downloadFileWithResume); extensions never see it, and
 * Komikku's own build adds its interceptor there only in a fork-marked
 * variant. If a future slice routes extension traffic through it, the
 * interceptor joins it then.
 */
class PlayerNetworkHelper(
    context: Context,
    preferences: NetworkPreferences,
    private val sessionCookieJar: SessionStoreCookieJar,
    private val challengeBridge: PlayerChallengeBridge
) : NetworkHelper(context, preferences) {

    override val cookieJar = sessionCookieJar

    override val client: OkHttpClient by lazy {
        super.client.newBuilder()
            .addInterceptor(CloudflareInterceptor(sessionCookieJar, challengeBridge))
            .build()
    }
}
