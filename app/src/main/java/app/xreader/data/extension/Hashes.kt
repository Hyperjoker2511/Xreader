package app.xreader.data.extension

import java.security.MessageDigest

/** SHA-256 helpers for the trust engine (PLAN §9.1): index TOFU pins,
 * artifact fingerprints, declarative-rule fingerprints. */
object Hashes {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** Constant-shape comparison for hashes (both are hex digests). */
    fun hashesEqual(a: String?, b: String?): Boolean =
        a != null && b != null && a.lowercase() == b.lowercase()
}
