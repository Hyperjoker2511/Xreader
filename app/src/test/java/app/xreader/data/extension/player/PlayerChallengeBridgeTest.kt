package app.xreader.data.extension.player

import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * K2: the challenge bridge — a prompt is raised, the session-saved bus
 * plus a store-verified clearance releases the waiter as solved, a
 * dismissal releases it as unsolved, and an unrelated save releases
 * nobody.
 */
class PlayerChallengeBridgeTest {

    private val url = "https://asurascans.com/chapter/1".toHttpUrl()

    private fun bridge(
        bus: MutableSharedFlow<String>,
        clearance: AtomicBoolean,
        timeoutMillis: Long = 5_000
    ) = PlayerChallengeBridge(
        sessionSavedEvents = bus,
        hasClearance = { clearance.get() },
        timeoutMillis = timeoutMillis
    )

    @Test
    fun `saved session with clearance releases the waiter as solved`() {
        val bus = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val clearance = AtomicBoolean(false)
        val bridge = bridge(bus, clearance)

        val result = AtomicReference<Boolean?>()
        val waiter = Thread { result.set(bridge.requestSolve(url)) }
        waiter.start()

        awaitTrue { bridge.prompts.value?.host == "asurascans.com" }
        clearance.set(true)
        // Emit until the waiter wakes (idempotent; covers the bus
        // collector's subscription timing on a loaded test JVM).
        val deadline = System.currentTimeMillis() + 4_000
        while (waiter.isAlive && System.currentTimeMillis() < deadline) {
            bus.tryEmit(SessionStoreCookieJar.PLAYER_SESSION_KEY)
            Thread.sleep(50)
        }

        waiter.join(5_000)
        assertEquals(true, result.get())
        awaitTrue { bridge.prompts.value == null }
    }

    @Test
    fun `dismissal releases the waiter as unsolved`() {
        val bus = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val bridge = bridge(bus, AtomicBoolean(false))

        val result = AtomicReference<Boolean?>()
        val waiter = Thread { result.set(bridge.requestSolve(url)) }
        waiter.start()

        awaitTrue { bridge.prompts.value != null }
        bridge.dismissCurrent()

        waiter.join(5_000)
        assertEquals(false, result.get())
    }

    @Test
    fun `a save for another key does not release the waiter`() {
        val bus = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val clearance = AtomicBoolean(false)
        val bridge = bridge(bus, clearance)

        val result = AtomicReference<Boolean?>()
        val waiter = Thread { result.set(bridge.requestSolve(url)) }
        waiter.start()

        awaitTrue { bridge.prompts.value != null }
        clearance.set(true)
        bus.tryEmit("ext-999")
        Thread.sleep(400)
        assertTrue("waiter must still be blocked", waiter.isAlive)

        bridge.dismissCurrent()
        waiter.join(5_000)
        // The dismissal path re-checks the store: the clearance that
        // arrived under the wrong key event still counts for the host.
        assertEquals(true, result.get())
    }

    @Test
    fun `clearance already present returns at once with no prompt`() {
        val bus = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val bridge = bridge(bus, AtomicBoolean(true))
        assertTrue(bridge.requestSolve(url))
        assertNull(bridge.prompts.value)
    }

    private fun awaitTrue(deadlineMillis: Long = 3_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMillis
        while (!condition()) {
            if (System.currentTimeMillis() > end) {
                fail("condition not met within ${deadlineMillis}ms")
            }
            Thread.sleep(20)
        }
    }
}
