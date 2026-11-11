package app.xreader.di

import android.app.Application
import android.content.SharedPreferences
import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.player.InMemoryPreferenceStore
import app.xreader.data.extension.player.PlayerApplicationContext
import app.xreader.data.extension.player.PlayerChallengeBridge
import app.xreader.data.extension.player.PlayerNetworkHelper
import app.xreader.data.extension.player.PlayerRegistrar
import app.xreader.data.extension.player.SessionStoreCookieJar
import app.xreader.data.extension.testVault
import app.xreader.komikku.KomikkuReaderRegistrar
import app.xreader.komikku.setting.InMemoryKomikkuStore
import app.xreader.komikku.setting.ReaderPreferences
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * v1.5.3 (finding #1, spec item 2): the device-path gate test for
 * the startup Injekt bootstrap.
 *
 * The phone proof showed the whole Keiyoushi shelf at "0 ready":
 * BOTH registrars called `patchInjekt()` inside `register()`, and
 * `patchInjekt()` REPLACES the global Injekt scope with a fresh
 * empty one. AppContainer registers the player first and the
 * Komikku reader second, so the reader's patch silently wiped all
 * six PlayerModule registrations — the player had NOTHING at
 * runtime, and every extension died either at construction
 * (Asura: `Injekt.get<Application>()`) or at its first data call
 * (HttpSource: `Injekt.get<NetworkHelper>()`). The per-half tests
 * never saw it because each registrar passes in isolation.
 *
 * This test reproduces AppContainer's EXACT startup sequence —
 * `patchInjekt()` once (item 1: moved into the container), then
 * PlayerRegistrar, then KomikkuReaderRegistrar — and asserts the
 * union of both modules survives it. Run against the pre-fix
 * bootstrap (each registrar patching inside `register()`), the
 * second patch wipes the player module and the assertions below
 * fail; that failure was verified during the fix's development.
 *
 * Registration is one-time and process-wide by design, so the
 * sequence runs exactly once, in production order — from the
 * first test method's @Before, behind a one-time guard.
 *
 * Why not @BeforeClass (the v1.5.3 pre-gate repair): Robolectric
 * sets up application state per test method, so
 * `RuntimeEnvironment.getApplication()` returns null while
 * class fixtures run. The original @BeforeClass bootstrap died
 * on exactly that — a bare NullPointerException at the
 * PlayerNetworkHelper call, the null platform-typed Application
 * flowing into a non-null Context parameter — before a single
 * assertion could execute. In @Before the sandbox application
 * exists. A bare `Application()` (the trick PlayerRegistrarTest
 * gets away with) is not an option here: PlayerApplicationContext
 * delegates getSharedPreferences to its wrapped base, so the
 * base must be a functioning application for the Asura-shaped
 * test below to mean anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StartupInjektBootstrapTest {

    /**
     * The Asura trigger, shaped from the K0 disassembly in
     * finding #1's report: the extension's preferences delegate
     * resolves the Application from Injekt IN ITS CONSTRUCTOR
     * (`getPreferences { … }` → `applicationContext` →
     * `Injekt.get<Application>()`, then `getSharedPreferences`).
     * If the Application registration was wiped, construction
     * itself throws — the source never exists.
     */
    private class AsuraShapedSource {
        val prefs: SharedPreferences =
            Injekt.get<Application>().getSharedPreferences("source_1", 0)
    }

    @Test
    fun `both modules survive the production startup order`() {
        assertTrue(PlayerRegistrar.isRegistered())
        assertTrue(KomikkuReaderRegistrar.isRegistered())
        // The six PlayerModule registrations (finding #1):
        assertNotNull(Injekt.get<Application>())
        assertNotNull(Injekt.get<NetworkHelper>())
        assertNotNull(Injekt.get<JavaScriptEngine>())
        assertNotNull(Injekt.get<Json>())
        assertNotNull(Injekt.get<ProtoBuf>())
        assertNotNull(Injekt.get<XML>())
        // …and the Komikku reader's, registered second:
        assertNotNull(Injekt.get<ReaderPreferences>())
    }

    @Test
    fun `a source shaped like Asura constructs after startup`() {
        val source = AsuraShapedSource()
        assertNotNull(source.prefs)
    }

    /**
     * The production bootstrap, run exactly once (guarded by
     * [bootstrapped]) from the first @Before that executes —
     * see the class KDoc for why this cannot live in
     * @BeforeClass under Robolectric.
     */
    @Before
    fun bootstrapInProductionOrder() {
        if (bootstrapped) return
        bootstrapped = true
        val app = RuntimeEnvironment.getApplication()
        val vault = testVault()
        val helper = PlayerNetworkHelper(
            app,
            NetworkPreferences(InMemoryPreferenceStore()),
            SessionStoreCookieJar(SessionStore(vault), vault),
            PlayerChallengeBridge(
                sessionSavedEvents =
                    MutableSharedFlow(extraBufferCapacity = 4),
                hasClearance = { false }
            )
        )
        // AppContainer.init, in order (item 1): the patch
        // happens ONCE, here, before either registrar imports
        // its module into the one scope.
        patchInjekt()
        PlayerRegistrar.register(PlayerApplicationContext(app), helper)
        KomikkuReaderRegistrar.register(InMemoryKomikkuStore())
    }

    companion object {
        /** One-time guard: the production bootstrap runs once. */
        private var bootstrapped = false
    }
}
