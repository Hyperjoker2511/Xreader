package app.xreader.data.extension.player

/**
 * v1.5.0 (steer B1): the online-reading half of the player's
 * image road. Downloads already fetched a page's bytes
 * through the source's OWN image request (its headers on
 * its own OkHttp client — [MihonSourceAdapter.pageBytes]);
 * the reader's Coil path could only present a URL plus a
 * Referer, so a source whose pages need its full request —
 * or whose client descrambles the bytes in an interceptor —
 * read scrambled online while its downloads were fine.
 *
 * The bridge closes that: when an adapter resolves a
 * chapter's pages it registers each page URL here with a
 * byte fetch that runs the source's own request. The app's
 * Coil loader carries a fetcher that claims exactly the
 * registered URLs and serves those bytes (Coil's decoders
 * then do their usual sizing). Everything unregistered
 * takes the normal network path, untouched.
 *
 * Registrations are bounded (a long reading session must
 * not grow this without limit): eldest entries evict past
 * [MAX_ENTRIES]; an evicted page simply falls back to the
 * plain URL path.
 */
object PlayerImageBridge {

    /** A page's bytes through its own source's request, or
     * null when the source can't serve it that way. */
    fun interface ByteFetch {
        suspend fun fetch(url: String): ByteArray?
    }

    const val MAX_ENTRIES = 2_000

    private val fetches = object :
        LinkedHashMap<String, ByteFetch>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ByteFetch>?
        ): Boolean = size > MAX_ENTRIES
    }

    fun register(url: String, fetch: ByteFetch) {
        if (url.isBlank()) return
        synchronized(fetches) { fetches[url] = fetch }
    }

    fun knows(url: String): Boolean =
        synchronized(fetches) { fetches.containsKey(url) }

    suspend fun fetch(url: String): ByteArray? {
        val fetch = synchronized(fetches) { fetches[url] }
            ?: return null
        return runCatching { fetch.fetch(url) }.getOrNull()
    }
}
