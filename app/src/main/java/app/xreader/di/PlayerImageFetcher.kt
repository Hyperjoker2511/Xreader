package app.xreader.di

import app.xreader.data.extension.player.PlayerImageBridge
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer

/**
 * v1.5.0 (steer B1): the Coil fetcher for Keiyoushi player
 * pages (see [PlayerImageBridge]). It claims ONLY string
 * URLs the bridge has registered — a player chapter's page
 * addresses — and serves the bytes the source's own image
 * request returned, so the extension's headers and any
 * descrambling interceptor on its client run for online
 * reading exactly as they do for downloads. Local files,
 * novel covers and every unregistered URL fall through to
 * Coil's normal fetchers.
 *
 * v1.5.6 (§5.2, M2): the claim now also covers URLs the
 * bridge no longer holds but a player source OWNS (their
 * registration was evicted). For those, [fetch] first
 * re-resolves the image request through the owning adapter
 * (the bridge's resolver seam); if re-resolution fails, the
 * fetch THROWS — the page takes the visible error state —
 * instead of the factory declining and Coil's plain fetcher
 * serving the page headerless. A page never silently
 * degrades to a plain fetch.
 */
class PlayerImageFetcher(
    private val url: String,
    private val context: android.content.Context
) : Fetcher {

    override suspend fun fetch(): SourceResult {
        val bytes = PlayerImageBridge.fetchOrReresolve(url)
        if (bytes == null || bytes.isEmpty()) {
            // The source resolved this URL (or owns it); an
            // empty answer from its own request — after
            // re-resolution was attempted — is a load failure
            // like any other (Coil shows the request's error
            // state). Never a silent plain-fetch fallback.
            throw java.io.IOException(
                "the source's image request came back empty"
            )
        }
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), context),
            mimeType = null,
            dataSource = DataSource.NETWORK
        )
    }

    class Factory : Fetcher.Factory<String> {
        override fun create(
            data: String,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            if (!PlayerImageClaim.claims(data)) return null
            return PlayerImageFetcher(data, options.context)
        }
    }
}

/**
 * v1.5.6 (M2): the factory's claim decision, pure so the
 * harness can pin it without a Coil runtime: an http(s) URL
 * is the player fetcher's when the bridge holds its byte
 * fetch OR a player source owns it (evicted registration —
 * re-resolution happens in [PlayerImageFetcher.fetch]).
 * Anything else declines to Coil's normal fetchers.
 */
object PlayerImageClaim {
    fun claims(url: String): Boolean {
        if (!url.startsWith("http://") &&
            !url.startsWith("https://")
        ) {
            return false
        }
        return PlayerImageBridge.knows(url) ||
            PlayerImageBridge.ownerOf(url) != null
    }
}
