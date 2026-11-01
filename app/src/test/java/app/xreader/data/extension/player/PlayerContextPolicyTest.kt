package app.xreader.data.extension.player

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K2: per-extension context behaviours — prefs/cache namespacing, the
 * startActivity funnel (policy + refusal recording), and the thread
 * namespace holder the wrapper reads.
 */
class PlayerContextPolicyTest {

    @Test
    fun `prefs files are namespaced per extension`() {
        val asura = PlayerContextPolicy.prefsFileName(
            "eu.kanade.tachiyomi.extension.en.asurascans", "asura_prefs"
        )
        val aqua = PlayerContextPolicy.prefsFileName(
            "eu.kanade.tachiyomi.extension.en.aquamanga", "asura_prefs"
        )
        assertNotEquals(asura, aqua)
        assertTrue(asura.startsWith("keiyoushi_"))
        assertTrue(asura.endsWith("_asura_prefs"))
    }

    @Test
    fun `same extension with different names stays apart`() {
        val a = PlayerContextPolicy.prefsFileName("ext.one", "first")
        val b = PlayerContextPolicy.prefsFileName("ext.one", "second")
        assertNotEquals(a, b)
    }

    @Test
    fun `blank requested name falls back to a stable per-extension default`() {
        val a = PlayerContextPolicy.prefsFileName("ext.one", null)
        val b = PlayerContextPolicy.prefsFileName("ext.one", "  ")
        assertEquals(a, b)
        assertNotEquals(a, PlayerContextPolicy.prefsFileName("ext.two", null))
    }

    @Test
    fun `cache dirs differ per extension`() {
        assertNotEquals(
            PlayerContextPolicy.cacheDirName("ext.one"),
            PlayerContextPolicy.cacheDirName("ext.two")
        )
        assertEquals(
            "eu.kanade.tachiyomi.extension.en.asurascans",
            PlayerContextPolicy.cacheDirName(
                "eu.kanade.tachiyomi.extension.en.asurascans"
            )
        )
    }

    @Test
    fun `sanitize strips path separators and spaces`() {
        assertEquals("a_b", PlayerContextPolicy.sanitize("a/b"))
        assertEquals("a_b", PlayerContextPolicy.sanitize("a b"))
        assertFalse(PlayerContextPolicy.sanitize("../x").contains('/'))
    }

    @Test
    fun `view intents to http and https are allowed`() {
        assertTrue(
            PlayerContextPolicy.isAllowedStartActivity(
                Intent.ACTION_VIEW, "https", hasComponent = false
            )
        )
        assertTrue(
            PlayerContextPolicy.isAllowedStartActivity(
                Intent.ACTION_VIEW, "http", hasComponent = false
            )
        )
    }

    @Test
    fun `component intents are refused`() {
        // Component intents name donor-app activities (e.g. a WebView
        // activity) that do not exist in Xreader.
        assertFalse(
            PlayerContextPolicy.isAllowedStartActivity(
                Intent.ACTION_VIEW, "https", hasComponent = true
            )
        )
    }

    @Test
    fun `non-view actions and non-web schemes are refused`() {
        assertFalse(
            PlayerContextPolicy.isAllowedStartActivity(
                Intent.ACTION_SEND, "https", hasComponent = false
            )
        )
        assertFalse(
            PlayerContextPolicy.isAllowedStartActivity(
                Intent.ACTION_VIEW, "intent", hasComponent = false
            )
        )
        assertFalse(
            PlayerContextPolicy.isAllowedStartActivity(
                null, null, hasComponent = false
            )
        )
    }

    @Test
    fun `gate records refusals and only refusals`() {
        val gate = StartActivityGate()
        assertTrue(
            gate.allow(Intent.ACTION_VIEW, "https", false, "allowed-intent")
        )
        assertFalse(
            gate.allow(Intent.ACTION_VIEW, "https", true, "component-intent")
        )
        assertEquals(1, gate.refusals.size)
        assertTrue(gate.refusals.single().contains("component=true"))
        assertTrue(gate.refusals.single().contains("component-intent"))
    }

    @Test
    fun `namespace applies inside the block and restores after`() {
        assertEquals(
            PlayerContextState.SHARED_NAMESPACE,
            PlayerContextState.currentNamespace()
        )
        PlayerContextState.withNamespace("ext-a") {
            assertEquals("ext-a", PlayerContextState.currentNamespace())
            PlayerContextState.withNamespace("ext-b") {
                assertEquals("ext-b", PlayerContextState.currentNamespace())
            }
            assertEquals("ext-a", PlayerContextState.currentNamespace())
        }
        assertEquals(
            PlayerContextState.SHARED_NAMESPACE,
            PlayerContextState.currentNamespace()
        )
    }
}
