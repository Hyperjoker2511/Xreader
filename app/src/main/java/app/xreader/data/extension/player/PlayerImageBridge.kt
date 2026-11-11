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
 * [MAX_ENTRIES].
 *
 * v1.5.6 (§5.2, M2) — the eviction law changed. Before, an
 * evicted page "simply fell back to the plain URL path":
 * Coil's plain fetcher served it WITHOUT the source's
 * headers/client — a silent degradation. Now a page NEVER
 * silently degrades to a headerless plain fetch:
 * registrations made through [registerOwned] also record
 * WHICH source owns the URL (in a separately bounded
 * ownership map that outlives the byte-fetch entries), and
 * each owner registers a [PageResolver]. When the fetcher
 * meets an owned URL whose byte fetch was evicted, it
 * re-resolves the image request through the owning adapter
 * ([reresolve]) before any fallback; if re-resolution
 * fails, the fetch fails visibly (the page takes the §5.4
 * error state) instead of falling through to the plain
 * path. URLs with no owner (covers, novel images, anything
 * never registered by a player adapter) are untouched:
 * they were never the bridge's pages.
 */
object PlayerImageBridge {

    /** A page's bytes through its own source's request, or
     * null when the source can't serve it that way. */
    fun interface ByteFetch {
        suspend fun fetch(url: String): ByteArray?
    }

    /**
     * v1.5.6 (M2): re-resolves one evicted page through its
     * owning adapter — the adapter re-registers the URL's
     * byte fetch (and, if its own retained page record is
     * gone too, re-resolves the page list). Returns true
     * when the URL is registered again afterwards.
     */
    fun interface PageResolver {
        suspend fun reresolve(url: String): Boolean
    }

    const val MAX_ENTRIES = 2_000

    /** Ownership records are two short strings per page, so
     * the map can afford to remember far more pages than
     * the byte-fetch map — ownership must outlive eviction
     * for the M2 law to hold across a long session. */
    const val MAX_OWNERSHIP_ENTRIES = 20_000

    private val fetches = object :
        LinkedHashMap<String, ByteFetch>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ByteFetch>?
        ): Boolean = size > MAX_ENTRIES
    }

    /** URL -> owning source's key (its namespaced id). */
    private val owners = object :
        LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String>?
        ): Boolean = size > MAX_OWNERSHIP_ENTRIES
    }

    /** Owning source key -> its re-resolution seam. */
    private val resolvers = HashMap<String, PageResolver>()

    fun register(url: String, fetch: ByteFetch) {
        if (url.isBlank()) return
        synchronized(fetches) { fetches[url] = fetch }
    }

    /** Register a page AND record which source owns it, so
     * an eviction can be healed through that source (M2). */
    fun registerOwned(url: String, ownerKey: String, fetch: ByteFetch) {
        if (url.isBlank() || ownerKey.isBlank()) return
        synchronized(fetches) { fetches[url] = fetch }
        synchronized(owners) { owners[url] = ownerKey }
    }

    /** Register (or replace) a source's re-resolution seam. */
    fun registerResolver(ownerKey: String, resolver: PageResolver) {
        if (ownerKey.isBlank()) return
        synchronized(resolvers) { resolvers[ownerKey] = resolver }
    }

    /** The key of the source that owns [url], or null when
     * no player source ever registered it. */
    fun ownerOf(url: String): String? =
        synchronized(owners) { owners[url] }

    fun knows(url: String): Boolean =
        synchronized(fetches) { fetches.containsKey(url) }

    suspend fun fetch(url: String): ByteArray? {
        val fetch = synchronized(fetches) { fetches[url] }
            ?: return null
        return runCatching { fetch.fetch(url) }.getOrNull()
    }

    /**
     * v1.5.6 (M2): ask the owning source to re-register
     * [url]'s byte fetch. True when the URL is registered
     * again afterwards; false when the URL has no owner, the
     * owner has no resolver, or re-resolution failed.
     */
    suspend fun reresolve(url: String): Boolean {
        if (knows(url)) return true
        val owner = ownerOf(url) ?: return false
        val resolver = synchronized(resolvers) { resolvers[owner] }
            ?: return false
        runCatching { resolver.reresolve(url) }
        return knows(url)
    }

    /**
     * v1.5.6 (M2): the fetcher's read — the registered byte
     * fetch first; on a miss, re-resolution through the
     * owning source, then the (freshly re-registered) fetch.
     * Null means the page cannot be served through its
     * source at all: the caller must surface an error, never
     * fall back to a plain headerless fetch.
     */
    suspend fun fetchOrReresolve(url: String): ByteArray? {
        fetch(url)?.let { return it }
        if (reresolve(url)) return fetch(url)
        return null
    }

    /** Test seam: the bridge is a process singleton, so JVM
     * harnesses reset it to model a cold process / a clean
     * session. Production never calls this. */
    fun clearForTest() {
        synchronized(fetches) { fetches.clear() }
        synchronized(owners) { owners.clear() }
        synchronized(resolvers) { resolvers.clear() }
    }
}
