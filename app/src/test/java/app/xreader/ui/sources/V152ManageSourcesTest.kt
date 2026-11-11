package app.xreader.ui.sources

import app.xreader.di.AppContainer
import app.xreader.data.extension.MoonSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.2 (FIX-15): the source-management journeys, over the
 * REAL container (Robolectric) — the toggle writes the
 * existing §8.1 enabled flag through RepoManager.setEnabled
 * and hidden sources leave the global-search fan-out.
 *
 * Mutation dependence (the re-scan cuts these lines):
 *  - ManageSourcesViewModel.setEnabled's repoManager call:
 *    cut it and the toggle journey's DAO assertion goes red.
 *  - GlobalSearchViewModel's `.filter { it.extension.enabled }`
 *    fan-out gate: cut it and the hidden source answers the
 *    search again — the search journey goes red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V152ManageSourcesTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        installFakeAndroidKeyStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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

    /** Installs MoonRead through the real auto-install road
     * and returns the container holding it. */
    private suspend fun containerWithMoonRead(): AppContainer {
        val container = AppContainer(
            RuntimeEnvironment.getApplication(),
            extensionTransportOverride = MoonSite.transport()
        )
        val vm = LinkAddViewModel(container)
        vm.probe(MoonSite.BOOK_URL)
        val saved = withTimeoutOrNull(300_000L) {
            vm.state.first { it is LinkAddUiState.Saved }
        } ?: throw AssertionError(
            "MoonRead did not auto-install; state: ${vm.state.value}"
        )
        check(saved is LinkAddUiState.Saved)
        return container
    }

    @Test
    fun `toggle journey - off persists through the update pass and files under hidden`() =
        runBlocking {
            val container = containerWithMoonRead()
            val manage = ManageSourcesViewModel(container)
            val row = withTimeoutOrNull(300_000L) {
                manage.allRows.first { rows ->
                    rows.any { it.extension.displayName == "MoonRead" }
                }
            }?.first { it.extension.displayName == "MoonRead" }
                ?: throw AssertionError("MoonRead row never appeared")
            assertEquals(
                "a link-added source reads as such",
                "Added by you via link", row.provenanceLabel
            )
            assertTrue("ships shown", row.shown)

            manage.setEnabled(row, false)
            val dao = container.database.installedExtensionDao()
            val deadline = System.currentTimeMillis() + 60_000L
            var stored = dao.allNow().single()
            while (stored.enabled && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L)
                stored = dao.allNow().single()
            }
            assertFalse(
                "the toggle wrote the §8.1 flag", stored.enabled
            )

            // §8.1: the update pass must NOT resurrect a source
            // the user switched off — and must not delete it.
            container.updatePass152.runPhase0()
            val after = dao.allNow()
            assertEquals("the row survives the pass", 1, after.size)
            assertFalse(
                "still disabled after the pass", after.single().enabled
            )

            // The row files under Hidden, not Shown.
            manage.setShowing(ShowingFilter.HIDDEN)
            val hidden = withTimeoutOrNull(60_000L) {
                manage.rows.first { rows ->
                    rows.any { it.extension.displayName == "MoonRead" }
                }
            }
            assertTrue("row files under Hidden", hidden != null)
            manage.setShowing(ShowingFilter.SHOWN)
            Thread.sleep(300L)
            assertTrue(
                "row no longer files under Shown",
                manage.rows.value.none {
                    it.extension.displayName == "MoonRead"
                }
            )
            Unit
        }

    @Test
    fun `hidden source contributes nothing to global search`() =
        runBlocking {
            val container = containerWithMoonRead()
            val search = GlobalSearchViewModel(container)

            fun searchGroups(): List<GlobalSearchGroup> {
                search.setQuery("the")
                search.search()
                // Phase-2-only settle (round-9 fix): search()
                // dirties the state synchronously
                // (searched = false) before launching, so
                // this wait can only be satisfied by THIS
                // run's completion — the previous run's
                // settled state no longer matches.
                val deadline =
                    System.currentTimeMillis() + 300_000L
                while (true) {
                    val s = search.state.value
                    if (s.searched && !s.searching) break
                    check(System.currentTimeMillis() < deadline) {
                        "global search never settled: $s"
                    }
                    Thread.sleep(10L)
                }
                return search.state.value.groups
            }

            val before = searchGroups()
            assertTrue(
                "the shown source answers: ${before.map { it.sourceName }}",
                before.any { it.sourceName == "MoonRead" }
            )

            val manage = ManageSourcesViewModel(container)
            val row = withTimeoutOrNull(300_000L) {
                manage.allRows.first { rows ->
                    rows.any { it.extension.displayName == "MoonRead" }
                }
            }?.first { it.extension.displayName == "MoonRead" }
                ?: throw AssertionError("MoonRead row never appeared")
            manage.setEnabled(row, false)
            val dao = container.database.installedExtensionDao()
            val deadline = System.currentTimeMillis() + 60_000L
            while (dao.allNow().single().enabled &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50L)
            }
            assertFalse(dao.allNow().single().enabled)

            val hiddenRun = searchGroups()
            assertTrue(
                "hidden source is silent in global search: " +
                    hiddenRun.map { it.sourceName },
                hiddenRun.none { it.sourceName == "MoonRead" }
            )

            // Re-showing restores its voice.
            val stored = dao.allNow().single()
            container.repoManager.setEnabled(stored, true)
            val after = searchGroups()
            assertTrue(
                "re-shown source answers again: " +
                    after.map { it.sourceName },
                after.any { it.sourceName == "MoonRead" }
            )
            Unit
        }
}
