package app.xreader.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KeyVault round-trip on the JVM with a fake cipher (PLAN §3 gate).
 * The Android Keystore itself doesn't exist off-device — that limit is
 * stated in the Phase 1 report; what's pinned here is the vault's
 * storage logic: encode/decode format, presence cache, and the
 * defensive read (corrupt entry = absent, never a crash).
 */
class KeyVaultTest {

    /** Reversible stand-in for AES-GCM: tags the bytes so tampering with
     * the tag is detectable, like GCM's auth tag. */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            byteArrayOf(TAG) + plain

        override fun decrypt(data: ByteArray): ByteArray {
            require(data.isNotEmpty() && data[0] == TAG) { "bad tag" }
            return data.copyOfRange(1, data.size)
        }

        private companion object {
            const val TAG: Byte = 0x5A
        }
    }

    private class FakeStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }

        suspend fun rawPut(key: String, value: String) {
            updateData { prefs ->
                val mutable = prefs.toMutablePreferences()
                mutable[stringPreferencesKey(key)] = value
                mutable.toPreferences()
            }
        }
    }

    private fun vault(store: FakeStore) =
        KeyVault(store, FakeCipher(), CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `round trip stores ciphertext and returns plaintext`() = runBlocking {
        val store = FakeStore()
        val vault = vault(store)
        vault.put("gemini_api_key", "secret-value")
        assertEquals("secret-value", vault.get("gemini_api_key"))
        assertTrue(vault.isPresent("gemini_api_key"))
        // What's actually in storage is NOT the plaintext.
        val raw = store.data.first()[stringPreferencesKey("vault_gemini_api_key")]
        assertTrue(raw != null && !raw.contains("secret-value"))
    }

    @Test
    fun `absent and removed keys read as absent`() = runBlocking {
        val vault = vault(FakeStore())
        assertNull(vault.get("nope"))
        assertFalse(vault.isPresent("nope"))
        vault.put("k", "v")
        vault.remove("k")
        assertNull(vault.get("k"))
        assertFalse(vault.isPresent("k"))
    }

    @Test
    fun `corrupt entries read as absent, never crash`() = runBlocking {
        val store = FakeStore()
        val vault = vault(store)
        // Not valid Base64 at all.
        store.rawPut("vault_broken1", "!!!not-base64!!!")
        assertNull(vault.get("broken1"))
        // Valid Base64 but not cipher output (bad tag).
        store.rawPut(
            "vault_broken2",
            java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        )
        assertNull(vault.get("broken2"))
    }

    @Test
    fun `well known entry names are stable`() {
        assertEquals("gemini_api_key", KeyVault.GEMINI_KEY)
        assertEquals("opds_password_7", KeyVault.opdsPassword(7))
        assertEquals("session_cookies_ext1", KeyVault.sessionCookies("ext1"))
    }
}
