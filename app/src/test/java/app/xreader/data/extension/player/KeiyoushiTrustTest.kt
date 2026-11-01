package app.xreader.data.extension.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K3: the Keiyoushi trust decisions — fixture digests only.
 * Accept: the index's key is the known Keiyoushi key AND
 * every certificate in the APK hashes to it AND the library
 * is 1.6. Everything else refuses or marks incompatible,
 * with the reason in plain words.
 */
class KeiyoushiTrustTest {

    private val key = KeiyoushiTrust.EXPECTED_SIGNING_KEY

    @Test
    fun `the genuine keiyoushi key accepts`() {
        assertEquals(
            KeiyoushiTrust.Verdict.Accept,
            KeiyoushiTrust.verifyIndexKey(key)
        )
        // Case and whitespace do not matter.
        assertEquals(
            KeiyoushiTrust.Verdict.Accept,
            KeiyoushiTrust.verifyIndexKey("  ${key.uppercase()} ")
        )
    }

    @Test
    fun `a swapped index key refuses the whole catalogue`() {
        val verdict = KeiyoushiTrust.verifyIndexKey("a".repeat(64))
        assertTrue(verdict is KeiyoushiTrust.Verdict.Refuse)
        assertTrue(
            (verdict as KeiyoushiTrust.Verdict.Refuse).reason
                .contains("isn't the Keiyoushi key")
        )
    }

    @Test
    fun `a missing index key refuses`() {
        assertTrue(
            KeiyoushiTrust.verifyIndexKey(null)
                is KeiyoushiTrust.Verdict.Refuse
        )
        assertTrue(
            KeiyoushiTrust.verifyIndexKey("  ")
                is KeiyoushiTrust.Verdict.Refuse
        )
    }

    @Test
    fun `a certificate matching the index key accepts`() {
        assertEquals(
            KeiyoushiTrust.Verdict.Accept,
            KeiyoushiTrust.verifyCertificate(listOf(key), key)
        )
        assertEquals(
            KeiyoushiTrust.Verdict.Accept,
            KeiyoushiTrust.verifyCertificate(
                listOf(key.uppercase()), key
            )
        )
    }

    @Test
    fun `a foreign signer refuses`() {
        val verdict = KeiyoushiTrust.verifyCertificate(
            listOf("b".repeat(64)), key
        )
        assertTrue(verdict is KeiyoushiTrust.Verdict.Refuse)
        assertTrue(
            (verdict as KeiyoushiTrust.Verdict.Refuse).reason
                .contains("different key")
        )
    }

    @Test
    fun `one bad signer among several refuses`() {
        assertTrue(
            KeiyoushiTrust.verifyCertificate(
                listOf(key, "c".repeat(64)), key
            ) is KeiyoushiTrust.Verdict.Refuse
        )
    }

    @Test
    fun `an unsigned file refuses`() {
        assertTrue(
            KeiyoushiTrust.verifyCertificate(emptyList(), key)
                is KeiyoushiTrust.Verdict.Refuse
        )
    }

    @Test
    fun `lib 1_6 accepts and everything else is incompatible`() {
        assertEquals(
            KeiyoushiTrust.Verdict.Accept,
            KeiyoushiTrust.verifyLibVersion(1.6)
        )
        val old = KeiyoushiTrust.verifyLibVersion(1.4)
        assertTrue(old is KeiyoushiTrust.Verdict.Incompatible)
        assertTrue(
            (old as KeiyoushiTrust.Verdict.Incompatible).reason
                .contains("1.4")
        )
        assertTrue(
            KeiyoushiTrust.verifyLibVersion(null)
                is KeiyoushiTrust.Verdict.Incompatible
        )
    }

    @Test
    fun `lib version derives from metadata then version name`() {
        assertEquals(1.6, KeiyoushiTrust.libVersionOf(1.6f, "9.9.9")!!, 0.0)
        assertEquals(1.6, KeiyoushiTrust.libVersionOf(0.0f, "1.6.71")!!, 0.0)
        assertEquals(1.4, KeiyoushiTrust.libVersionOf(0.0f, "1.4.2")!!, 0.0)
        assertEquals(null, KeiyoushiTrust.libVersionOf(0.0f, null))
        assertEquals(null, KeiyoushiTrust.libVersionOf(0.0f, "beta"))
    }
}

/**
 * K3: load failures in plain words — the two named traps
 * (our OkHttp gate step is never the source's defect; the
 * script-engine gap has its own sentence) and class-naming
 * for everything else.
 */
class PlayerFailureWordsTest {

    @Test
    fun `an okhttp missing class reads as the G-K2-1 gate step`() {
        val failure = PlayerFailureWords.describe(
            NoClassDefFoundError("okhttp3/CompressionInterceptor")
        )
        assertTrue(failure.reason.contains("G-K2-1"))
        assertTrue(failure.reason.contains("can't run yet"))
        assertTrue(failure.detail.contains("CompressionInterceptor"))
    }

    @Test
    fun `a script engine refusal reads as the engine failing to start`() {
        // K6 wired the real engine; the stub-era refusal text
        // (and any QuickJS load failure) now reads as what it
        // is — the engine couldn't start on this phone.
        val failure = PlayerFailureWords.describe(
            UnsupportedOperationException(
                "JavaScriptEngine is not supported by Xreader's Keiyoushi player"
            )
        )
        assertTrue(
            failure.reason.contains(
                "its script engine couldn't start on this phone"
            )
        )
    }

    @Test
    fun `a missing script engine class reads the same way`() {
        val failure = PlayerFailureWords.describe(
            NoClassDefFoundError(
                "eu/kanade/tachiyomi/network/JavaScriptEngine"
            )
        )
        assertTrue(failure.reason.contains("script engine"))
    }

    @Test
    fun `another missing class is named`() {
        val failure = PlayerFailureWords.describe(
            ClassNotFoundException("com.example.NativeBridge")
        )
        assertTrue(failure.reason.contains("com.example.NativeBridge"))
        assertTrue(failure.reason.contains("isn't part of this app"))
    }

    @Test
    fun `a wrapped cause is found through the chain`() {
        val wrapped = RuntimeException(
            "load failed",
            NoClassDefFoundError("okhttp3/zstd/Zstd")
        )
        val failure = PlayerFailureWords.describe(wrapped)
        assertTrue(failure.reason.contains("G-K2-1"))
    }

    @Test
    fun `a plain failure keeps its exception name`() {
        val failure = PlayerFailureWords.describe(
            IllegalStateException("boom")
        )
        assertTrue(failure.reason.contains("IllegalStateException"))
        assertTrue(failure.detail.contains("boom"))
    }
}
