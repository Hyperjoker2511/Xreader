package app.xreader.data.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.data.prefs.SourcePrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * v1.7 Patch 1 (item 5): the DoH toggle — default OFF, and
 * ON routes source lookups through Cloudflare (1.1.1.1)
 * while OFF is byte-identical to the system resolver.
 */
class Patch1DohTest {

    private class RecordingDns(
        private val answer: InetAddress
    ) : Dns {
        var calls = 0
        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            return listOf(answer)
        }
    }

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    @Test
    fun `toggle defaults off`() = runTest {
        assertFalse(SourcePrefs(FakeDataStore()).dohEnabled.first())
    }

    @Test
    fun `toggle persists round-trip`() = runTest {
        val prefs = SourcePrefs(FakeDataStore())
        prefs.setDohEnabled(true)
        assertTrue(prefs.dohEnabled.first())
        prefs.setDohEnabled(false)
        assertFalse(prefs.dohEnabled.first())
    }

    @Test
    fun `switchable dns uses doh only while enabled`() {
        val doh = RecordingDns(InetAddress.getByName("1.1.1.1"))
        val system = RecordingDns(InetAddress.getByName("127.0.0.1"))
        val dns = SwitchableDns(doh = doh, system = system)

        // OFF (the default): the system resolver, untouched.
        assertEquals(listOf(systemAnswer()), dns.lookup("example.com"))
        assertEquals(0, doh.calls)
        assertEquals(1, system.calls)

        // ON: the DoH resolver, and the system one is not asked.
        dns.enabled = true
        assertEquals(listOf(dohAnswer()), dns.lookup("example.com"))
        assertEquals(1, doh.calls)
        assertEquals(1, system.calls)

        // Back OFF: the system resolver again.
        dns.enabled = false
        dns.lookup("example.com")
        assertEquals(1, doh.calls)
        assertEquals(2, system.calls)
    }

    private fun dohAnswer() = InetAddress.getByName("1.1.1.1")
    private fun systemAnswer() = InetAddress.getByName("127.0.0.1")

    @Test
    fun `resolver points at cloudflare 1-1-1-1`() {
        assertEquals("https://1.1.1.1/dns-query", DohDns.DOH_URL)
        // Construction only — no network: the resolver builds
        // over its bootstrap client without a lookup.
        DohDns.resolver()
    }

    @Test
    fun `source clients carry the given resolver`() {
        val dns = RecordingDns(InetAddress.getByName("1.1.1.1"))
        assertSame(dns, OkHttpTransport.defaultClient(dns).dns)
        assertSame(dns, OkHttpTransport.extensionClient(dns).dns)
        assertSame(dns, OkHttpTransport.searchClient(dns).dns)
    }

    @Test
    fun `source clients default to the system resolver`() {
        assertSame(Dns.SYSTEM, OkHttpTransport.defaultClient().dns)
        assertSame(Dns.SYSTEM, OkHttpTransport.extensionClient().dns)
        assertSame(Dns.SYSTEM, OkHttpTransport.searchClient().dns)
    }
}
