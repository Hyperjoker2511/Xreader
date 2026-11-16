package app.xreader.ui.more

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import app.xreader.di.AppContainer
import app.xreader.ui.localreader.V159ComposeHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Customise hub + pages (v1.6.2, SPEC-CUSTOMISE-ORION-2026-11-16):
 * the REAL CustomiseScreen over the REAL container, driven
 * through the V159 compose harness.
 *
 * - The hub renders the pinned Preview and all FIFTEEN doors
 *   with their plain titles, in the order the sections have
 *   always stood.
 * - A door's status line is a live value from state the
 *   screen already reads (spot-checked at defaults: the App
 *   size door reads "Normal · 1.00", the Navigation door
 *   "Gooey · Theme").
 * - Tapping a door opens its page — the page title shows and
 *   a text that lives ONLY on that page composes — and the
 *   page's back chevron returns to the hub (the marker is
 *   gone, the doors are back). The fifteen markers also pin
 *   the door↔page pairing: a swapped index would put the
 *   wrong body behind a door and fail its marker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [35],
    qualifiers = "w420dp-h900dp"
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomiseHubPagesTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        installFakeAndroidKeyStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Door titles in hub order, each with a text that is
     * composed ONLY on that door's page. */
    private val doorsAndMarkers = listOf(
        "Colour theme" to "Theme mode",
        "App size" to "How big everything is on this phone.",
        "Glass & surfaces" to "Background picture",
        "Library layout" to "Continue Reading card",
        "Navigation" to "Indicator preview",
        "Reader" to
            "Page themes — the preview is a real page in those colours.",
        "Manga reader" to
            "Manga reader (Komikku) — defaults for all books",
        "Your themes" to
            "Themes you made yourself, from one colour. Up to three.",
        "Book colours" to "Cover glow in Library",
        "Performance" to "Best looking",
        "Sources & repairs" to "Content shown from sources:",
        "Downloads" to "Auto-clean cache",
        "Storage" to "Clear caches",
        "Accessibility & motion" to "Move Me card with phone",
        "Advanced" to "Reset customisation"
    )

    @Test
    fun `hub renders the preview and all fifteen doors`() {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val viewModel = CustomiseViewModel(container)
        val handle = V159ComposeHarness.compose {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CustomiseScreen(viewModel = viewModel, onBack = {})
            }
        }

        // The pinned live preview rides on top of the hub.
        assertNotNull(
            "the Preview card",
            V159ComposeHarness.findByText(handle, "Preview")
        )
        assertNotNull(
            "the sample book",
            V159ComposeHarness.findByText(handle, "A sample book")
        )

        // All fifteen doors, plain titles.
        doorsAndMarkers.forEach { (title, _) ->
            assertNotNull(
                "$title door",
                V159ComposeHarness.findByText(handle, title)
            )
        }

        // Status lines are live values, spot-checked at the
        // fresh-install defaults.
        assertNotNull(
            "App size status line",
            V159ComposeHarness.findByText(handle, "Normal · 1.00")
        )
        assertNotNull(
            "Navigation status line",
            V159ComposeHarness.findByText(handle, "Gooey · Theme")
        )
    }

    @Test
    fun `every door opens its page and back returns to the hub`() {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val viewModel = CustomiseViewModel(container)
        val handle = V159ComposeHarness.compose {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CustomiseScreen(viewModel = viewModel, onBack = {})
            }
        }

        doorsAndMarkers.forEachIndexed { index, (title, marker) ->
            assertTrue(
                "$title door tappable",
                V159ComposeHarness.clickByLabel(handle, title)
            )
            // The page title shows (the page's header) and the
            // page-only marker composes.
            assertNotNull(
                "$title page title",
                awaitText(handle, title)
            )
            assertNotNull(
                "$title page body ($marker)",
                awaitText(handle, marker)
            )
            // Back to the hub: the marker leaves the tree and
            // the doors are back. (After the Advanced page the
            // hub proof is the Colour theme door — "Advanced"
            // itself is that page's title, so it cannot prove
            // the hub is back.)
            assertTrue(
                "back from $title",
                V159ComposeHarness.clickByLabel(
                    handle, "Back to all settings"
                )
            )
            assertNull(
                "$title page closed ($marker gone)",
                awaitGone(handle, marker)
            )
            val hubProof = if (index == doorsAndMarkers.lastIndex) {
                "Colour theme"
            } else {
                "Advanced"
            }
            assertNotNull(
                "hub is back after $title",
                awaitText(handle, hubProof)
            )
        }
    }

    /** Waits (in harness idles) for [text] to compose — the
     * hub↔page swap animates on the app's stack transitions,
     * and one page (Storage) fills in behind a disk walk. */
    private fun awaitText(
        handle: V159ComposeHarness.Handle,
        text: String
    ): Any? {
        repeat(10) {
            V159ComposeHarness.findByText(handle, text)?.let { return it }
            V159ComposeHarness.idle()
        }
        return V159ComposeHarness.findByText(handle, text)
    }

    /** Waits for [text] to leave the tree (the outgoing page
     * rides its exit transition out). Returns the node if it
     * is STILL there after the wait — the failure shape. */
    private fun awaitGone(
        handle: V159ComposeHarness.Handle,
        text: String
    ): Any? {
        repeat(10) {
            if (V159ComposeHarness.findByText(handle, text) == null) {
                return null
            }
            V159ComposeHarness.idle()
        }
        return V159ComposeHarness.findByText(handle, text)
    }

    private fun installFakeAndroidKeyStore() {
        if (java.security.Security.getProvider("AndroidKeyStore") != null) {
            return
        }
        java.security.Security.addProvider(FakeAndroidKeyStoreProvider())
    }

    private class FakeAndroidKeyStoreProvider :
        java.security.Provider(
            "AndroidKeyStore", 1.0, "in-memory test keystore"
        ) {
        init {
            put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", FakeKeyGeneratorSpi::class.java.name)
        }
    }

    companion object {
        private val fakeKeys =
            java.util.concurrent.ConcurrentHashMap<String, javax.crypto.SecretKey>()
    }

    class FakeKeyStoreSpi : java.security.KeyStoreSpi() {
        override fun engineGetKey(
            alias: String, password: CharArray?
        ): java.security.Key? = fakeKeys[alias]

        override fun engineGetEntry(
            alias: String,
            protParam: java.security.KeyStore.ProtectionParameter?
        ): java.security.KeyStore.Entry? =
            fakeKeys[alias]?.let { java.security.KeyStore.SecretKeyEntry(it) }

        override fun engineContainsAlias(alias: String): Boolean =
            fakeKeys.containsKey(alias)

        override fun engineLoad(
            stream: java.io.InputStream?, password: CharArray?
        ) = Unit

        override fun engineStore(
            stream: java.io.OutputStream?, password: CharArray?
        ) = Unit

        override fun engineGetCertificateChain(alias: String) = null
        override fun engineGetCertificate(alias: String) = null
        override fun engineGetCreationDate(alias: String) = java.util.Date(0)
        override fun engineSetKeyEntry(
            alias: String, key: java.security.Key, password: CharArray?,
            chain: Array<out java.security.cert.Certificate>?
        ) {
            (key as? javax.crypto.SecretKey)?.let { fakeKeys[alias] = it }
        }

        override fun engineSetKeyEntry(
            alias: String, key: ByteArray,
            chain: Array<out java.security.cert.Certificate>?
        ) = Unit

        override fun engineSetCertificateEntry(
            alias: String, cert: java.security.cert.Certificate
        ) = Unit

        override fun engineDeleteEntry(alias: String) {
            fakeKeys.remove(alias)
        }

        override fun engineAliases() =
            java.util.Collections.enumeration(fakeKeys.keys)

        override fun engineSize() = fakeKeys.size
        override fun engineIsKeyEntry(alias: String) =
            fakeKeys.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(
            cert: java.security.cert.Certificate
        ): String? = null
    }

    class FakeKeyGeneratorSpi : javax.crypto.KeyGeneratorSpi() {
        private var alias: String = "xreader.keyvault.v1"
        override fun engineInit(
            params: java.security.spec.AlgorithmParameterSpec?,
            random: java.security.SecureRandom
        ) {
            (params as? android.security.keystore.KeyGenParameterSpec)
                ?.let { alias = it.keystoreAlias }
        }

        override fun engineInit(random: java.security.SecureRandom) = Unit
        override fun engineInit(keysize: Int, random: java.security.SecureRandom) = Unit
        override fun engineGenerateKey(): javax.crypto.SecretKey {
            val generator = javax.crypto.KeyGenerator.getInstance("AES")
            generator.init(256)
            val key = generator.generateKey()
            fakeKeys[alias] = key
            return key
        }
    }
}
