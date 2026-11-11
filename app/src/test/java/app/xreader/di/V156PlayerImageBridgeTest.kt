package app.xreader.di

import app.xreader.data.extension.player.PlayerImageBridge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.5.6 (§5.2, M2): the law that a player page NEVER
 * silently degrades to a headerless plain fetch. When a
 * page's bridge registration is evicted, the fetcher's
 * claim still covers the URL (ownership survives), and the
 * fetch re-resolves through the OWNING source's resolver —
 * the plain path is only for URLs no player source owns.
 */
class V156PlayerImageBridgeTest {

    private val owner = "keiyoushi:test.pkg:42"
    private val pageUrl = "https://cdn.test/chapter1/p1.jpg"

    @Before
    fun setUp() = PlayerImageBridge.clearForTest()

    @After
    fun tearDown() = PlayerImageBridge.clearForTest()

    /** Evict [url]'s byte-fetch entry by flooding the
     * access-ordered map past MAX_ENTRIES. */
    private fun evict(url: String) {
        repeat(PlayerImageBridge.MAX_ENTRIES + 1) { i ->
            PlayerImageBridge.register("https://flood.test/$i.jpg") {
                byteArrayOf(0)
            }
        }
        assertFalse("precondition: $url evicted", PlayerImageBridge.knows(url))
    }

    @Test
    fun evictedOwnedPageReResolvesThroughTheOwningSource() = runBlocking {
        var resolverCalls = 0
        PlayerImageBridge.registerResolver(owner) { url ->
            resolverCalls++
            // The owning adapter re-registers the page.
            PlayerImageBridge.registerOwned(url, owner) {
                "via-source".toByteArray()
            }
            true
        }
        PlayerImageBridge.registerOwned(pageUrl, owner) {
            "original".toByteArray()
        }
        evict(pageUrl)

        // Ownership survives eviction, so the fetcher still
        // claims the URL — it must NOT decline to the plain
        // headerless path.
        assertEquals(owner, PlayerImageBridge.ownerOf(pageUrl))
        assertTrue(PlayerImageClaim.claims(pageUrl))

        val bytes = PlayerImageBridge.fetchOrReresolve(pageUrl)
        assertEquals("via-source", bytes?.let { String(it) })
        assertEquals(1, resolverCalls)
    }

    @Test
    fun emptyBridgeStartStillRoutesOwnedPageThroughSource() = runBlocking {
        // Cold start shape: ownership + resolver are known
        // (the adapter registered them) but NO byte fetch is
        // registered yet — the fetch must go through the
        // source's resolver, not the plain path.
        PlayerImageBridge.registerResolver(owner) { url ->
            PlayerImageBridge.registerOwned(url, owner) {
                "cold-source".toByteArray()
            }
            true
        }
        // Ownership without a live registration (the state
        // an eviction leaves, modelled directly).
        PlayerImageBridge.registerOwned(pageUrl, owner) {
            "stale".toByteArray()
        }
        evict(pageUrl)
        assertTrue(PlayerImageClaim.claims(pageUrl))
        val bytes = PlayerImageBridge.fetchOrReresolve(pageUrl)
        assertEquals("cold-source", bytes?.let { String(it) })
    }

    @Test
    fun failedReResolutionIsAVisibleFailureNeverAPlainFetch() = runBlocking {
        PlayerImageBridge.registerResolver(owner) { false }
        PlayerImageBridge.registerOwned(pageUrl, owner) {
            "original".toByteArray()
        }
        evict(pageUrl)
        // Claimed (so Coil will NOT serve it plainly)...
        assertTrue(PlayerImageClaim.claims(pageUrl))
        // ...and the source path answers null, which the
        // fetcher turns into a thrown load failure — the
        // page's visible Error state (§5.4).
        assertNull(PlayerImageBridge.fetchOrReresolve(pageUrl))
    }

    @Test
    fun unownedUrlsAreNotTheBridgePages() = runBlocking {
        val cover = "https://cdn.test/covers/some-book.jpg"
        assertNull(PlayerImageBridge.ownerOf(cover))
        assertFalse(PlayerImageClaim.claims(cover))
        assertFalse(PlayerImageBridge.reresolve(cover))
        assertNull(PlayerImageBridge.fetchOrReresolve(cover))
        // Non-http data (local files) never claims either.
        assertFalse(PlayerImageClaim.claims("/data/files/book/img-1.jpg"))
    }

    @Test
    fun registeredPageFetchesDirectlyWithoutResolver() = runBlocking {
        var resolverCalls = 0
        PlayerImageBridge.registerResolver(owner) {
            resolverCalls++
            true
        }
        PlayerImageBridge.registerOwned(pageUrl, owner) {
            "direct".toByteArray()
        }
        val bytes = PlayerImageBridge.fetchOrReresolve(pageUrl)
        assertEquals("direct", bytes?.let { String(it) })
        assertEquals(0, resolverCalls)
    }
}
