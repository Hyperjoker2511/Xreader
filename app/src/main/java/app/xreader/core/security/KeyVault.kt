package app.xreader.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.core.error.runCatchingAppSuspend
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where the user's secrets live (PLAN §3): the Gemini API key, OPDS
 * passwords, and saved login-session cookies (§9.2) — the ONLY class that
 * touches them. Secrets are encrypted with [SecretCipher] (Keystore
 * AES-256/GCM on device) and the ciphertext is stored as Base64 in a
 * private DataStore file. Plaintext exists only as the return value of
 * [get], in the caller's hands, for one request at a time.
 *
 * Secrets are never written to Room, never included in backups or sync
 * payloads (PLAN §7/§9.5), and never logged — this class has no logging.
 *
 * A synchronous presence cache ([presentKeys]) lets callers answer
 * "is this configured?" without suspending. It holds NAMES ONLY.
 *
 * Defensive reads (PLAN §1.5): a stored entry that fails to decode or
 * decrypt (corrupt write, Keystore key invalidated by a lock-screen
 * reset…) reads as "absent" instead of crashing.
 */
class KeyVault(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _presentKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Names of the entries currently stored — names only, never values. */
    val presentKeys: StateFlow<Set<String>> = _presentKeys.asStateFlow()

    init {
        // Keeps the presence cache in step with storage from process
        // start (a supervisor scope owned by the vault: a storage hiccup
        // must never take the app down).
        scope.launch {
            store.data.collect { prefs ->
                _presentKeys.value = prefs.asMap().keys
                    .map { it.name }
                    .filter { it.startsWith(ENTRY_PREFIX) }
                    .map { it.removePrefix(ENTRY_PREFIX) }
                    .toSet()
            }
        }
    }

    /** Synchronous presence check. */
    fun isPresent(name: String): Boolean = name in _presentKeys.value

    /** The decrypted secret, or null when absent/unreadable. */
    suspend fun get(name: String): String? {
        val raw = store.data.first()[entryKey(name)] ?: return null
        return runCatchingAppSuspend { decode(raw) }.getOrNull()
    }

    suspend fun put(name: String, secret: String) {
        val encoded = encode(secret)
        store.edit { prefs -> prefs[entryKey(name)] = encoded }
        _presentKeys.update { it + name }
    }

    suspend fun remove(name: String) {
        store.edit { prefs -> prefs.remove(entryKey(name)) }
        _presentKeys.update { it - name }
    }

    /** Plaintext → Base64(IV ‖ ciphertext). Internal so tests can pin the format. */
    internal fun encode(secret: String): String =
        Base64.getEncoder().encodeToString(cipher.encrypt(secret.toByteArray(Charsets.UTF_8)))

    /** Inverse of [encode]; throws on any malformed input (caller coerces). */
    internal fun decode(raw: String): String =
        String(cipher.decrypt(Base64.getDecoder().decode(raw)), Charsets.UTF_8)

    private fun entryKey(name: String) = stringPreferencesKey(ENTRY_PREFIX + name)

    companion object {
        /** DataStore key prefix; entry names never carry secret material. */
        const val ENTRY_PREFIX = "vault_"

        /** Well-known entry names (values are secrets; names are not). */
        const val GEMINI_KEY = "gemini_api_key"

        /** v1.0.3 (AI hub): the other built-in providers' keys. */
        const val OPENAI_KEY = "openai_api_key"
        const val CLAUDE_KEY = "anthropic_api_key"
        const val APINEX_KEY = "apinex_api_key"

        /** v1.0.3: a user-added provider's key, by its provider id. */
        fun providerKey(providerId: String) = "provider_key_$providerId"

        const val WEBDAV_PASSWORD = "webdav_password"
        fun opdsPassword(serverId: Long) = "opds_password_$serverId"
        fun sessionCookies(extensionId: String) = "session_cookies_$extensionId"

        /** v1.3.4 (session v2): a source's stored operation
         * (read) token record, by its source-scoped key. */
        fun readTokenKey(tokenKey: String) = "read_token_$tokenKey"
    }
}
