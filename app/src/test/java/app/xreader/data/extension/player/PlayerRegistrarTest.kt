package app.xreader.data.extension.player

import android.app.Application
import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.testVault
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
 * holds the suite's single registration.
 */
class PlayerRegistrarTest {

    @Test
    fun `registered singletons resolve to the player instances`() {
        val vault = testVault()
        val jar = SessionStoreCookieJar(SessionStore(vault), vault)
        val bridge = PlayerChallengeBridge(
            sessionSavedEvents = MutableSharedFlow(extraBufferCapacity = 4),
            hasClearance = { false }
        )
        val helper = PlayerNetworkHelper(
            Application(),
            NetworkPreferences(InMemoryPreferenceStore()),
            jar,
            bridge
        )
        val wrapper = PlayerApplicationContext(Application())

        PlayerRegistrar.register(wrapper, helper)

        assertTrue(PlayerRegistrar.isRegistered())
        assertSame(wrapper, Injekt.get<Application>())
        assertSame(helper, Injekt.get<NetworkHelper>())
        // The factory is a singleton factory: repeated lookups keep
        // returning the same helper (HttpSource caches it per source,
        // but the registry must not hand out a second one either).
        assertSame(helper, Injekt.get<NetworkHelper>())
    }
}
