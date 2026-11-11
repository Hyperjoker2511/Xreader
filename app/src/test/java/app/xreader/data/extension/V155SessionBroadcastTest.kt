package app.xreader.data.extension

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.5 (§3, reality check c): the session-update broadcast
 * harness. The production seam — [SessionUpdateBus] +
 * [SurfaceRetryGate], the exact objects AppContainer and the
 * five surfaces use — is driven with scripted saves and fake
 * surfaces:
 *
 *  - TWO subscribed surfaces BOTH retry their own operations
 *    for one save (the exactly-once claim starved all but
 *    one surface; that is the defect this build removes);
 *  - a BACKSTACK surface defers until it is next resumed,
 *    then retries exactly once;
 *  - capability scoping: a BOTI-shaped source (cookie→header
 *    binding, no read-token endpoint, not needsRendering)
 *    never qualifies for the WebView mint or the transport
 *    switch — it keeps the broadcast + OkHttp road exactly;
 *  - the solved-at window is the in-memory session with the
 *    15-minute floor (Amendments), not 60 seconds.
 */
class V155SessionBroadcastTest {

    /** One fake surface at the production seam: the same
     * gate + event handling the ViewModels run, with its
     * "own operation" reduced to a counter + key log. */
    private class FakeSurface(
        val name: String,
        resumed: Boolean
    ) {
        val resumedFlag = AtomicBoolean(resumed)
        val retries = AtomicInteger(0)
        val retriedKeys = mutableListOf<String>()

        val gate = SurfaceRetryGate(name) { resumedFlag.get() }

        /** What the surface's bus collector does on an event. */
        fun onEvent(key: String) {
            if (gate.onSessionUpdated(key)) retry(key)
        }

        /** What the surface's resume watcher does. */
        fun onResumed() {
            gate.onResumed()?.let { retry(it) }
        }

        private fun retry(key: String) {
            retries.incrementAndGet()
            synchronized(retriedKeys) { retriedKeys += key }
        }
    }

    @Test
    fun `one save retries BOTH subscribed surfaces`() = runBlocking {
        SessionSolvedRegistry.clearForTests()
        val bus = SessionUpdateBus()
        val reader = FakeSurface("reader", resumed = true)
        val bookPage = FakeSurface("book-detail", resumed = true)
        val seen = mutableListOf<SessionUpdatedEvent>()
        // Two independent collectors on the ONE broadcast —
        // the production shape (each surface collects the
        // bus itself).
        val jobA = launch {
            bus.events.collect { event ->
                seen += event
                reader.onEvent(event.sessionKey)
            }
        }
        val jobB = launch {
            bus.events.collect { event ->
                bookPage.onEvent(event.sessionKey)
            }
        }
        delay(50)
        bus.notifySaved("ext-7", sourceId = "fenrir-realm")
        delay(50)
        jobA.cancel()
        jobB.cancel()
        // No claim anywhere: both surfaces re-issued their
        // OWN operation for the same save.
        assertEquals(1, reader.retries.get())
        assertEquals(1, bookPage.retries.get())
        assertEquals(listOf("ext-7"), reader.retriedKeys)
        assertEquals(listOf("ext-7"), bookPage.retriedKeys)
        // The event is source-scoped: key AND source id ride.
        assertEquals(1, seen.size)
        assertEquals("fenrir-realm", seen[0].sourceId)
        // And the solve was stamped for the mint-order window.
        assertTrue(SessionSolvedRegistry.hasRecentSolve("ext-7"))
    }

    @Test
    fun `backstack surface defers until resumed then retries once`() {
        val bus = SessionUpdateBus()
        val backstack = FakeSurface("catalogue", resumed = false)
        bus.notifySaved("ext-9", sourceId = null)
        backstack.onEvent("ext-9")
        assertEquals(0, backstack.retries.get())
        // Still backstack: no retry leaks early.
        backstack.onResumed()
        assertEquals(0, backstack.retries.get())
        // Resumed: the parked retry fires, exactly once.
        backstack.resumedFlag.set(true)
        backstack.onResumed()
        assertEquals(1, backstack.retries.get())
        backstack.onResumed()
        assertEquals(1, backstack.retries.get())
    }

    @Test
    fun `boti shaped source never qualifies for webview roads`() {
        val botiShaped = DeclarativeConfig(
            id = "boti-shaped",
            name = "BOTI Shaped",
            baseUrl = "https://boti.example",
            popular = ListSpec(
                url = "https://boti.example/popular",
                results = "div.result",
                title = "a.t",
                link = "a.t@href"
            ),
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul li a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "div.content"),
            needsRendering = false,
            credentials = CredentialSpec(
                headerBinding = HeaderBinding(
                    cookieName = "userToken",
                    headerName = "Authorization",
                    prefix = "",
                    hosts = listOf("api.example")
                )
            )
        )
        assertFalse(SessionCapabilityRouting.hasReadToken(botiShaped))
        assertFalse(SessionCapabilityRouting.webViewMintAllowed(botiShaped))
        assertFalse(
            SessionCapabilityRouting.webViewTransportSwitchAllowed(botiShaped)
        )
    }

    @Test
    fun `read token source qualifies for mint and switch`() {
        val fenrirShaped = DeclarativeConfig(
            id = "fenrir-shaped",
            name = "Fenrir Shaped",
            baseUrl = "https://fenrir.example",
            popular = ListSpec(
                url = "https://fenrir.example/popular",
                results = "div.result",
                title = "a.t",
                link = "a.t@href"
            ),
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul li a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "div.content"),
            credentials = CredentialSpec(
                operationToken = OperationTokenSpec(
                    mintUrl = "https://fenrir.example/api/read-token",
                    mintBodyTemplate = "{\"chapter_id\":\"{chapter_id}\"}"
                )
            )
        )
        assertTrue(SessionCapabilityRouting.hasReadToken(fenrirShaped))
        assertTrue(SessionCapabilityRouting.webViewMintAllowed(fenrirShaped))
        assertTrue(
            SessionCapabilityRouting.webViewTransportSwitchAllowed(fenrirShaped)
        )
    }

    @Test
    fun `solve window is fifteen minutes not sixty seconds`() {
        SessionSolvedRegistry.clearForTests()
        val solvedAt = 1_000_000L
        SessionSolvedRegistry.noteSolved("ext-3", atMs = solvedAt)
        // Five minutes later: still warm (the base spec's
        // 60-second window would already be cold).
        assertTrue(
            SessionSolvedRegistry.hasRecentSolve(
                "ext-3", nowMs = solvedAt + 5 * 60_000L
            )
        )
        // Sixteen minutes later: cold.
        assertFalse(
            SessionSolvedRegistry.hasRecentSolve(
                "ext-3", nowMs = solvedAt + 16 * 60_000L
            )
        )
        SessionSolvedRegistry.clearForTests()
    }
}
