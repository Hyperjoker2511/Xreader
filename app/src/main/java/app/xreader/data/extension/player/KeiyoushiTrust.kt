package app.xreader.data.extension.player

/**
 * K3 (Keiyoushi player): the trust decisions — pure, so the
 * accept/reject rules are pinned by JVM tests with fixture
 * digests. The flow they serve (donor-faithful, Komikku's
 * ExtensionLoader + TrustExtension restated for our anchor):
 *
 * 1. The index publishes ONE signing key for all its APKs.
 *    That key is the anchor — but the index is itself fetched
 *    data, so a key that isn't the Keiyoushi key we ship
 *    ([EXPECTED_SIGNING_KEY], the sanity constant) refuses
 *    the whole catalogue: a swapped index must not be able
 *    to bless its own files.
 * 2. An APK loads only when EVERY signing certificate in it
 *    hashes (SHA-256) to that key. A mismatch is a hard
 *    refusal, never a warning.
 * 3. The extension-library gate accepts 1.6 only (the whole
 *    current repo, verified in K0); anything else is
 *    INCOMPATIBLE with the reason shown, not a failure.
 */
object KeiyoushiTrust {

    /** The Keiyoushi signing key, SHA-256 of the certificate
     * — published in the index itself and verified against
     * three real APKs in K0. Used ONLY as the sanity check
     * on the index's own claim. */
    const val EXPECTED_SIGNING_KEY =
        "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

    /** The one library version this player runs. */
    const val SUPPORTED_LIB_VERSION = 1.6

    sealed interface Verdict {
        data object Accept : Verdict
        data class Refuse(val reason: String) : Verdict
        data class Incompatible(val reason: String) : Verdict
    }

    /** The index's claimed signing key must BE the Keiyoushi
     * key — otherwise nothing the index vouches for is
     * trusted. */
    fun verifyIndexKey(indexSigningKey: String?): Verdict {
        val key = indexSigningKey?.trim()?.lowercase()
        if (key.isNullOrBlank()) {
            return Verdict.Refuse(
                "the catalogue doesn't publish its signing key, " +
                    "so its files can't be checked"
            )
        }
        if (key != EXPECTED_SIGNING_KEY) {
            return Verdict.Refuse(
                "the catalogue's signing key isn't the Keiyoushi key " +
                    "Xreader knows — its files weren't trusted"
            )
        }
        return Verdict.Accept
    }

    /** Every certificate digest of the APK must equal the
     * index-published key (a single signer in practice; the
     * all-of rule is the donor's). */
    fun verifyCertificate(
        certDigestsSha256: List<String>,
        indexSigningKey: String
    ): Verdict {
        if (certDigestsSha256.isEmpty()) {
            return Verdict.Refuse(
                "the file isn't signed, so it can't be checked"
            )
        }
        val expected = indexSigningKey.trim().lowercase()
        val mismatch = certDigestsSha256.firstOrNull {
            it.trim().lowercase() != expected
        }
        if (mismatch != null) {
            return Verdict.Refuse(
                "the file is signed by a different key than the " +
                    "catalogue publishes — it wasn't installed"
            )
        }
        return Verdict.Accept
    }

    /**
     * The donor's lib-version derivation: the APK's
     * `tachiyomix.extensionLib` metadata when present,
     * otherwise the major.minor of the version name
     * ("1.6.71" → 1.6). Null when neither says anything.
     */
    fun libVersionOf(
        metadataLib: Float,
        versionName: String?
    ): Double? {
        // The manifest stores the value as a float32, so a direct
        // widening leaks binary noise (1.6f → 1.600000023841858,
        // which then also poisons the gate's reason text). The
        // shortest-string form of the float IS the decimal the
        // packager wrote (Float.toString(1.6f) == "1.6"), so derive
        // through it and land on the exact decimal.
        if (metadataLib != 0.0f) {
            return metadataLib.toString().toDoubleOrNull()
                ?: metadataLib.toDouble()
        }
        // The donor's fallback: major.minor of the version
        // name ("1.6.71" → "1.6"); a name without a dot is
        // taken whole.
        return versionName?.substringBeforeLast('.')?.toDoubleOrNull()
            ?: versionName?.toDoubleOrNull()
    }

    /** Accept exactly [SUPPORTED_LIB_VERSION]; anything else
     * is INCOMPATIBLE (the file is genuine — this player just
     * doesn't run its library generation). */
    fun verifyLibVersion(libVersion: Double?): Verdict {
        if (libVersion == null) {
            return Verdict.Incompatible(
                "it doesn't say which extension library it needs, " +
                    "so this version can't run it"
            )
        }
        if (kotlin.math.abs(libVersion - SUPPORTED_LIB_VERSION) > 0.001) {
            return Verdict.Incompatible(
                "it needs extension library $libVersion — " +
                    "this version runs $SUPPORTED_LIB_VERSION"
            )
        }
        return Verdict.Accept
    }
}

/**
 * K3: load failures in plain words, with the CAUSE kept
 * honest. The two named traps from the plan:
 *
 * - An OkHttp NoClassDefFoundError is OUR missing gate step
 *   (G-K2-1, the donor-stack flip to OkHttp 5) — it must
 *   never read as a defect in the source.
 * - A JavaScriptEngine failure: K6 wired the real QuickJS
 *   engine (the K1 stub is gone), so what remains is the
 *   engine itself failing to start — the QuickJS classes or
 *   native library failing to load on this device.
 *
 * Anything else class-shaped names the missing class — the
 * problem-details view keeps it, the row shows the words.
 */
object PlayerFailureWords {

    data class Failure(val reason: String, val detail: String)

    private const val JS_ENGINE_CLASS =
        "eu.kanade.tachiyomi.network.JavaScriptEngine"

    fun describe(t: Throwable): Failure {
        val chain = generateSequence(t) { it.cause }.toList()
        val detail = chain.joinToString(" ← ") { e ->
            "${e.javaClass.simpleName}: ${e.message ?: "-"}"
        }

        // The script engine failing to start, in any of its
        // shapes (its class or the QuickJS classes missing,
        // a stub-era refusal still bundled anywhere).
        val jsHit = chain.firstOrNull { e ->
            (e is UnsupportedOperationException &&
                e.message?.contains("JavaScriptEngine") == true) ||
                missingClassOf(e) == JS_ENGINE_CLASS ||
                missingClassOf(e)?.contains("quickjs", ignoreCase = true) == true
        }
        if (jsHit != null) {
            return Failure(
                reason = "its script engine couldn't start " +
                    "on this phone",
                detail = detail
            )
        }

        // Our OkHttp gate step, never the source's fault.
        val missing = chain.firstNotNullOfOrNull { missingClassOf(it) }
        if (missing != null && missing.startsWith("okhttp3.")) {
            return Failure(
                reason = "needs the newer network library — that " +
                    "arrives with a planned update (gate step " +
                    "G-K2-1), so it can't run yet",
                detail = detail
            )
        }

        // Injekt asked for something the player doesn't
        // register (only Application + NetworkHelper exist).
        val injektHit = chain.firstOrNull { e ->
            e.javaClass.name.contains("injekt", ignoreCase = true) ||
                e.message?.contains("injekt", ignoreCase = true) == true
        }
        if (injektHit != null) {
            return Failure(
                reason = "it asked the player for something the " +
                    "player doesn't provide",
                detail = detail
            )
        }

        // A missing class anywhere else: name it.
        if (missing != null) {
            return Failure(
                reason = "it needs $missing, which isn't part " +
                    "of this app",
                detail = detail
            )
        }

        val root = chain.last()
        return Failure(
            reason = "it couldn't be started " +
                "(${root.javaClass.simpleName})",
            detail = detail
        )
    }

    /** The class a linkage failure names, in dotted form —
     * NoClassDefFoundError's message is the internal name
     * ("okhttp3/CompressionInterceptor"), ClassNotFound-
     * Exception's is already dotted. */
    fun missingClassOf(t: Throwable): String? = when (t) {
        is NoClassDefFoundError ->
            t.message?.trim()?.replace('/', '.')
        is ClassNotFoundException ->
            t.message?.trim()?.substringBefore(" ")
        else -> null
    }
}
