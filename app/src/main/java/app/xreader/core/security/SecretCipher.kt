package app.xreader.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The encryption primitive behind [KeyVault] — split out so the vault's
 * storage logic is unit-testable on the JVM with a fake cipher (the
 * Android Keystore itself does not exist off-device; that limit is
 * stated honestly in the Phase 1 report).
 *
 * Contract: [encrypt] output is self-describing (everything [decrypt]
 * needs travels with the bytes); [decrypt] throws on corrupt/tampered
 * input — callers treat a throw as "no usable secret", never a crash.
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/**
 * AES-256/GCM backed by the Android Keystore (PLAN §3 — the KeyVault that
 * replaces the spec's deprecated EncryptedSharedPreferences). The key is
 * generated inside the Keystore and never leaves it; only IV ‖ ciphertext
 * bytes ever touch storage. Output layout: 12-byte GCM IV followed by the
 * ciphertext (tag appended by GCM).
 */
class AndroidKeystoreCipher : SecretCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        require(data.size > GCM_IV_BYTES) { "ciphertext too short" }
        val iv = data.copyOfRange(0, GCM_IV_BYTES)
        val body = data.copyOfRange(GCM_IV_BYTES, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "xreader.keyvault.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
