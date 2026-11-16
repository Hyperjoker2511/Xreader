package app.xreader.ui.more

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import app.xreader.data.library.NavIndicatorColourMode
import app.xreader.data.library.NavIndicatorStyle
import app.xreader.di.AppContainer
import app.xreader.ui.localreader.V159ComposeHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Nav-bar styles wiring (v1.6.2, spec test plan): the REAL
 * CustomiseScreen over the REAL container (the V152 journey
 * shape, driven through the V159 compose harness).
 *
 * - The Navigation page (opened by its hub door — Orion
 *   remake, SPEC-CUSTOMISE-ORION-2026-11-16; the door tap
 *   below is the same call the accordion header tap was,
 *   and every assertion is the pre-remake one at full
 *   strength) carries the live preview, the seventeen
 *   indicator-style chips (nav engine round,
 *   SPEC-NAV-ENGINE-2026-11-16 §2 — plain names, spec order;
 *   Wave appended by SPEC-NAV-TRAVEL-WAVE-2026-11-16 §C)
 *   and the colour row.
 * - Every style chip writes its pref through the ViewModel;
 *   the colour writer flips the mode to CUSTOM; the Theme
 *   chip flips it back.
 *
 * Mutation dependence: cut CustomiseViewModel.setNavIndicatorStyle's
 * prefs call and the chip assertions go red; cut
 * setNavIndicatorColour's and the colour assertions go red.
 *
 * Hardened 2026-11-16 (owner's word) after CI run 43812775622:
 * 2,892 tests, this the only failure — one chip's single
 * `first {}` wait timed out at line 197 while the app-side path
 * (chips wired 1:1, one generic ViewModel writer, prefs
 * round-trip green for all sixteen) audited sound. The drive
 * now scrolls each chip into view before tapping it (what a
 * finger does) and waits by POLLING the current value with one
 * idempotent re-tap at the halfway mark, naming the last seen
 * value on failure. Assertions unchanged in strength: every
 * chip must still land its enum, both colour flips must land.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [35],
    qualifiers = "w420dp-h900dp"
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomiseNavIndicatorWiringTest {

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
    fun `navigation section drives the indicator prefs`() {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val viewModel = CustomiseViewModel(container)
        val handle = V159ComposeHarness.compose {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CustomiseScreen(viewModel = viewModel, onBack = {})
            }
        }

        // The door is present on the hub and opens its page.
        assertNotNull(
            "Navigation door on the hub",
            V159ComposeHarness.findByText(handle, "Navigation")
        )
        assertTrue(
            "Navigation door opens its page",
            V159ComposeHarness.clickByLabel(handle, "Navigation")
        )

        // The preview and both control rows compose.
        assertNotNull(
            "the live preview",
            V159ComposeHarness.findByText(handle, "Indicator preview")
        )
        assertNotNull(
            "Indicator style row",
            V159ComposeHarness.findByText(handle, "Indicator style")
        )
        // All seventeen chips, plain names, in the spec's
        // order (WAVE appended by SPEC-NAV-TRAVEL-WAVE-
        // 2026-11-16 §C — addition only).
        val chipStyles = listOf(
            "Gooey" to NavIndicatorStyle.GOOEY,
            "Worm" to NavIndicatorStyle.WORM,
            "Liquid lens" to NavIndicatorStyle.LENS,
            "Slide" to NavIndicatorStyle.SLIDE,
            "Bubble" to NavIndicatorStyle.BUBBLE,
            "Notch" to NavIndicatorStyle.NOTCH,
            "Expanding" to NavIndicatorStyle.EXPANDING,
            "Segmented" to NavIndicatorStyle.SEGMENTED,
            "Square" to NavIndicatorStyle.SQUIRCLE,
            "Ring" to NavIndicatorStyle.RING,
            "Dot" to NavIndicatorStyle.DOT,
            "Top line" to NavIndicatorStyle.TOPLINE,
            "Glow" to NavIndicatorStyle.GLOW,
            "Minimal" to NavIndicatorStyle.MINIMAL,
            "Flashy" to NavIndicatorStyle.FLASHY,
            "Plain" to NavIndicatorStyle.PLAIN,
            "Wave" to NavIndicatorStyle.WAVE
        )
        chipStyles.forEach { (label, _) ->
            assertNotNull(
                "$label chip",
                V159ComposeHarness.findByText(handle, label)
            )
        }
        assertNotNull(
            "Indicator colour row",
            V159ComposeHarness.findByText(handle, "Indicator colour")
        )
        assertNotNull(
            "Theme chip",
            V159ComposeHarness.findByText(handle, "Theme")
        )
        assertNotNull(
            "Custom chip",
            V159ComposeHarness.findByText(handle, "Custom…")
        )
        assertNotNull(
            "the reduce-motion caption",
            V159ComposeHarness.findByText(
                handle,
                "The marker that travels under the tabs. With Reduce motion " +
                    "on, every style rests as Plain."
            )
        )
        assertNotNull(
            "the expanding and flashy caption",
            V159ComposeHarness.findByText(
                handle,
                "Expanding and Flashy show the name only on the tab you " +
                    "are on — Expanding puts it inside the coloured pill, " +
                    "Flashy swaps the icon for the name."
            )
        )

        // Every chip lands its enum through the ViewModel. Each
        // chip is scrolled into view before the tap — the page
        // is long, and a finger can only tap what is on screen.
        chipStyles.forEach { (label, style) ->
            V159ComposeHarness.scrollUntilDisplayed(handle, label)
            assertTrue(
                "$label chip clickable",
                V159ComposeHarness.clickByLabel(handle, label)
            )
            awaitStyle(container, style) {
                V159ComposeHarness.clickByLabel(handle, label)
            }
        }

        // The colour writer flips the mode to CUSTOM...
        viewModel.setNavIndicatorColour(0xFF336699L)
        val custom = pollState(container) {
            it.navIndicatorColourMode ==
                NavIndicatorColourMode.CUSTOM &&
                it.navIndicatorColourArgb == 0xFF336699L
        }
        assertTrue(
            "colour choice never landed " +
                "(last seen: ${lastSeen(container)})",
            custom
        )

        // ...and the Theme chip flips it back.
        V159ComposeHarness.scrollUntilDisplayed(handle, "Theme")
        assertTrue(
            "Theme chip clickable",
            V159ComposeHarness.clickByLabel(handle, "Theme")
        )
        val themed = pollState(container) {
            it.navIndicatorColourMode == NavIndicatorColourMode.THEME
        }
        assertTrue(
            "theme choice never landed " +
                "(last seen: ${lastSeen(container)})",
            themed
        )
    }

    /**
     * Polls the prefs state's CURRENT value until [matches] or
     * the deadline passes. Each poll is a fresh `first()` read
     * of the current snapshot (DataStore serves it from memory
     * after the first read), so a slow or conflated emission on
     * a loaded runner cannot strand the wait the way a single
     * collecting `first {}` could. [reclick], when given, fires
     * ONCE at the halfway mark: a chip tap is idempotent (it
     * writes the same value again), so a tap lost to a
     * mid-recomposition semantics snapshot is retried without
     * weakening what is asserted — the value must still land.
     */
    private fun pollState(
        container: AppContainer,
        timeoutMs: Long = 45_000L,
        reclick: (() -> Unit)? = null,
        matches: (app.xreader.data.library.LibraryState) -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() +
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var reclicked = false
        while (true) {
            val current = runBlocking {
                container.libraryPrefs.state.first()
            }
            if (matches(current)) return true
            val remainingMs = java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0L) return false
            if (reclick != null && !reclicked &&
                remainingMs < timeoutMs / 2
            ) {
                reclicked = true
                reclick()
            }
            Thread.sleep(minOf(150L, remainingMs))
        }
    }

    private fun lastSeen(container: AppContainer): String {
        val s = runBlocking { container.libraryPrefs.state.first() }
        return "style=${s.navIndicatorStyle} " +
            "mode=${s.navIndicatorColourMode} " +
            "argb=${s.navIndicatorColourArgb}"
    }

    private fun awaitStyle(
        container: AppContainer,
        expected: NavIndicatorStyle,
        reclick: () -> Unit
    ) {
        val landed = pollState(container, reclick = reclick) {
            it.navIndicatorStyle == expected
        }
        assertTrue(
            "indicator style never became $expected " +
                "(last seen: ${lastSeen(container)})",
            landed
        )
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
