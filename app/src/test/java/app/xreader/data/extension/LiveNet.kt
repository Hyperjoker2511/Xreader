package app.xreader.data.extension

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.5.2 live-proof plumbing: the JVM test worker has no proxy
 * configuration of its own, while this machine's egress goes
 * through the proxy named by the standard environment variables.
 * Installs the matching java.net proxy properties (host/port only
 * ever read from the environment; any credentials stay in memory
 * inside an [Authenticator] and are never printed or asserted on).
 * A no-op on machines with direct egress (no env proxy set).
 */
object LiveNet {
    private val installed = AtomicBoolean(false)

    fun installEgressProxyFromEnv() {
        if (!installed.compareAndSet(false, true)) return
        val raw = System.getenv("https_proxy")
            ?: System.getenv("HTTPS_PROXY")
            ?: System.getenv("http_proxy")
            ?: System.getenv("HTTP_PROXY")
            ?: return
        val uri = runCatching { URI(raw) }.getOrNull() ?: return
        val host = uri.host ?: return
        val port = if (uri.port > 0) uri.port else 3128
        for (scheme in listOf("http", "https")) {
            System.setProperty("$scheme.proxyHost", host)
            System.setProperty("$scheme.proxyPort", port.toString())
            System.setProperty(
                "$scheme.nonProxyHosts",
                "localhost|127.0.0.1|[::1]"
            )
        }
        val userInfo = uri.userInfo
        if (!userInfo.isNullOrEmpty()) {
            val user = userInfo.substringBefore(':')
            val pass = userInfo.substringAfter(':', "")
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication():
                    PasswordAuthentication =
                    PasswordAuthentication(user, pass.toCharArray())
            })
        }
    }

    /**
     * Absorbs the egress proxy's cold-path stall before a live
     * proof starts timing its legs. Measured on this machine
     * (2026-11-03): a fresh JVM's first HTTPS calls through the
     * proxy intermittently stall 10–15 s+ (read timeouts) while
     * the proxy warms the upstream route; once warm, the same
     * calls answer in ~200 ms. Every test JVM starts cold, so
     * the live proofs flaked ~half their runs with 120 s
     * "server took too long" stalls that curl (warm path)
     * never showed. This warm-up is sandbox plumbing only —
     * it touches no app timeout, retry or assertion: it fires
     * a few throwaway GETs at the proof's own endpoints until
     * one answers, bounded, failures ignored.
     */
    fun warmUp(vararg urls: String) {
        for (url in urls) {
            var attempts = 0
            var warm = false
            while (!warm && attempts < 4) {
                attempts++
                warm = runCatching {
                    val conn = java.net.URL(url).openConnection()
                        as java.net.HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.setRequestProperty("User-Agent", "Xreader-LiveProof")
                    conn.responseCode
                    conn.inputStream.use { stream ->
                        val buf = ByteArray(4096)
                        while (stream.read(buf) != -1) Unit
                    }
                    true
                }.getOrDefault(false)
            }
        }
    }
}
