package app.xreader.ui.more

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import app.xreader.di.AppContainer
import app.xreader.ui.localreader.V159ComposeHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
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
 * App size wiring (v1.6.2, spec test plan): the REAL
 * CustomiseScreen over the REAL container (the V152 journey
 * shape, driven through the V159 compose harness).
 *
 * - The "App size" section is present in the Customise index,
 *   right under Colour theme.
 * - Its Small chip writes 0.85 to the appearance pref through
 *   the ViewModel, and its slider drives the same pref.
 * - "Accessibility & motion" no longer carries the retired
 *   "App text size" stepper.
 *
 * Mutation dependence: cut CustomiseViewModel.setAppSize's
 * prefs call and the chip/slider assertions go red; restore
 * the old stepper row and the absence assertion goes red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [35],
    qualifiers = "w420dp-h900dp"
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomiseAppSizeWiringTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        installFakeAndroidKeyStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `app size section drives the pref and the text stepper is gone`() {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val viewModel = CustomiseViewModel(container)
        val handle = V159ComposeHarness.compose {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CustomiseScreen(viewModel = viewModel, onBack = {})
            }
        }

        // The section is present in the index.
        assertNotNull(
            "App size section in the index",
            V159ComposeHarness.findByText(handle, "App size")
        )

        // Open it: chips, slider and the plain line compose.
        assertTrue(
            "App size section opens",
            V159ComposeHarness.clickByLabel(handle, "App size")
        )
        assertNotNull(
            "Small chip",
            V159ComposeHarness.findByText(handle, "Small")
        )
        assertNotNull(
            "Normal chip",
            V159ComposeHarness.findByText(handle, "Normal")
        )
        assertNotNull(
            "Large chip",
            V159ComposeHarness.findByText(handle, "Large")
        )
        assertNotNull(
            "the plain line",
            V159ComposeHarness.findByText(
                handle,
                "Small on a small phone, Normal on a big one. This changes " +
                    "the whole screen — cards, text and dock — not just the words."
            )
        )

        // The Small chip writes the pref through the ViewModel.
        assertTrue(
            "Small chip clickable",
            V159ComposeHarness.clickByLabel(handle, "Small")
        )
        awaitAppSize(container, 0.85f)

        // The slider drives the same pref to the floor.
        val slider = handle.nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsActions.SetProgress) != null
        }
        assertNotNull("the App size slider is composed", slider)
        slider!!.config.getOrNull(SemanticsActions.SetProgress)
            ?.action?.invoke(0.70f)
        awaitAppSize(container, 0.70f)

        // Accessibility & motion keeps Reduce motion; the
        // retired text-size stepper is nowhere on the screen.
        assertTrue(
            "Accessibility & motion opens",
            V159ComposeHarness.clickByLabel(handle, "Accessibility & motion")
        )
        assertNotNull(
            "Reduce motion stays",
            V159ComposeHarness.findByText(handle, "Reduce motion")
        )
        assertNull(
            "the App text size stepper is gone",
            V159ComposeHarness.findByText(handle, "App text size")
        )
    }

    private fun awaitAppSize(container: AppContainer, expected: Float) {
        val reached = runBlocking {
            withTimeoutOrNull(30_000L) {
                container.appearancePrefs.state.first { it.appSize == expected }
            }
        }
        assertNotNull("app size never became $expected", reached)
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
