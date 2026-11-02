package app.xreader.data.extension.player

import android.app.Application
import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.testVault
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * K2: the registrar puts the two things real extensions ask Injekt for
 * into the registry — the Application (our per-extension wrapper) and
 * the NetworkHelper (the player's instance) — typed exactly as the
 * lookups type them.
 *
 * Registration is process-wide and one-time by design, so this class
 * holds the suite's single registration: it happens once, in
 * [registerOnce], and every test here asserts against those shared
 * instances.
 */
class PlayerRegistrarTest {

    /** v1.4.3 (R1): a KeiSource-shaped search row (synthetic). */
    @Serializable
    private data class FakeSeries(
        val name: String,
        val url: String,
        val thumbnail_url: String? = null
    )

    @Test
    fun `registered singletons resolve to the player instances`() {
        assertTrue(PlayerRegistrar.isRegistered())
        assertSame(wrapper, Injekt.get<Application>())
        assertSame(helper, Injekt.get<NetworkHelper>())
        // The factory is a singleton factory: repeated lookups keep
        // returning the same helper (HttpSource caches it per source,
        // but the registry must not hand out a second one either).
        assertSame(helper, Injekt.get<NetworkHelper>())
    }

    /**
     * v1.4.3 (R1) — the tripwire for the field diagnosis's
     * TRUNK finding: modern Keiyoushi extensions resolve Json
     * / ProtoBuf / XML from Injekt on their FIRST data call
     * (the Keiyoushi core's `jsonInstance = Injekt.get()`
     * feeds every `parseAs`), and the player originally
     * registered none of them — sources installed and opened,
     * then died on the first catalogue fetch with "it asked
     * the player for something the player doesn't provide".
     * The registered Json must also parse the way an
     * extension parses: a source-shaped payload carrying
     * fields the DTO doesn't declare (ignoreUnknownKeys) and
     * omitting an optional one (explicitNulls = false).
     */
    @Test
    fun `json protobuf and xml resolve and json parses extension style`() {
        assertNotNull(Injekt.get<Json>())
        assertNotNull(Injekt.get<ProtoBuf>())
        assertNotNull(Injekt.get<XML>())
        val parsed = Injekt.get<Json>().decodeFromString<FakeSeries>(
            """{"name":"Synthetic Blade","url":"/series/synthetic-blade",""" +
                """"status":1,"artist":"Some Studio","extra":{"x":true}}"""
        )
        assertEquals("Synthetic Blade", parsed.name)
        assertEquals("/series/synthetic-blade", parsed.url)
        assertEquals(null, parsed.thumbnail_url)
    }

    companion object {
        private lateinit var wrapper: PlayerApplicationContext
        private lateinit var helper: PlayerNetworkHelper

        @JvmStatic
        @BeforeClass
        fun registerOnce() {
            val vault = testVault()
            val jar = SessionStoreCookieJar(SessionStore(vault), vault)
            val bridge = PlayerChallengeBridge(
                sessionSavedEvents =
                    MutableSharedFlow(extraBufferCapacity = 4),
                hasClearance = { false }
            )
            helper = PlayerNetworkHelper(
                Application(),
                NetworkPreferences(InMemoryPreferenceStore()),
                jar,
                bridge
            )
            wrapper = PlayerApplicationContext(Application())
            PlayerRegistrar.register(wrapper, helper)
        }
    }
}
