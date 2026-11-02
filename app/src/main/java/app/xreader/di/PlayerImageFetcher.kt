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
 * Coil's normal fetchers. A bridge miss or empty answer is
 * also a fall-through (the factory declines), never a
 * broken page.
 */
class PlayerImageFetcher(
    private val url: String,
    private val context: android.content.Context
) : Fetcher {

    override suspend fun fetch(): SourceResult {
        val bytes = PlayerImageBridge.fetch(url)
        if (bytes == null || bytes.isEmpty()) {
            // The factory claimed this URL because the
            // source resolved it; an empty answer from its
            // own request is a load failure like any other
            // (Coil shows the request's error state).
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
            if (!data.startsWith("http://") &&
                !data.startsWith("https://")
            ) {
                return null
            }
            if (!PlayerImageBridge.knows(data)) return null
            return PlayerImageFetcher(data, options.context)
        }
    }
}
