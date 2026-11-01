package app.xreader.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 slice A: consent words must not promise an isolation
 * boundary DEX does not have, and availability parks must run
 * on a monotonic duration clock.
 */
class P0HardeningTest {

    @Test
    fun `declarative consent says data only and makes no isolation promise`() {
        val summary = ExtensionPermissions(
            domains = listOf("novels.example")
        ).consentSummary(ExtensionKind.DECLARATIVE.label)

        assertEquals(
            "This rule-based source (no code runs) can open: " +
                "novels.example. It provides reading rules only — data, " +
                "not program code — and Xreader uses those rules to " +
                "read the site.",
            summary
        )
        assertFalse(summary.contains("cannot see your books"))
        assertFalse(summary.contains("saved passwords"))
    }

    @Test
    fun `dex consent says app permissions and no sandbox`() {
        val summary = ExtensionPermissions(
            domains = listOf("dex.example"),
            canNotify = true
        ).consentSummary(ExtensionKind.DEX.label)

        assertEquals(
            "This app-code source declares these sites: dex.example. " +
                "It may show notifications. Its DEX code runs inside " +
                "Xreader with the app's own permissions. It is not " +
                "sandboxed or kept separate from the app, so only enable " +
                "it if you completely trust this source.",
            summary
        )
        assertFalse(summary.contains("cannot see your books"))
        assertFalse(summary.contains("saved passwords"))
    }

    @Test
    fun `dex remains opt in by default`() {
        assertFalse(ExtensionPermissions().dexOptIn)
    }

    @Test
    fun `cooldown uses elapsed time and a longer park wins`() {
        var elapsedMs = 1_000L
        val cooldowns = AvailabilityCooldowns { elapsedMs }

        cooldowns.record("source", retryAfterSeconds = 2, status = 429)
        assertEquals(2_000L, cooldowns.remainingMs("source"))
        assertEquals(429, cooldowns.originStatus("source"))

        elapsedMs += 500
        assertEquals(1_500L, cooldowns.remainingMs("source"))

        // A shorter later park never shortens the existing one.
        cooldowns.record("source", retryAfterSeconds = 1, status = 503)
        assertEquals(1_500L, cooldowns.remainingMs("source"))

        // A longer one extends from the current elapsed time.
        cooldowns.record("source", retryAfterSeconds = 3, status = 503)
        assertEquals(3_000L, cooldowns.remainingMs("source"))
        assertEquals(503, cooldowns.originStatus("source"))

        elapsedMs += 3_000
        assertEquals(0L, cooldowns.remainingMs("source"))
        assertEquals(null, cooldowns.originStatus("source"))
    }

    @Test
    fun `default cooldown records a live park`() {
        val cooldowns = AvailabilityCooldowns()
        cooldowns.record("source", retryAfterSeconds = 60, status = 429)

        val remaining = cooldowns.remainingMs("source")
        assertTrue(remaining in 1..60_000L)
        assertEquals(429, cooldowns.originStatus("source"))
    }
}
