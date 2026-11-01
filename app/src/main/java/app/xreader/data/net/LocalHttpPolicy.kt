package app.xreader.data.net

import app.xreader.core.error.AppError
import java.net.InetAddress
import java.net.URI

/**
 * v1.3.5 (P0, cleartext policy): the app's own rule for plain
 * `http://` addresses, enforced in code at the two places a
 * USER-SUPPLIED server address is dialled — the AI-provider hub
 * (Ollama and custom providers) and the WebDAV sync backend.
 *
 * https is always fine. Plain http is acceptable ONLY when the
 * host is on the user's own equipment:
 *  - loopback: `localhost` (or a `*.localhost` name), 127.0.0.0/8, ::1
 *  - RFC1918 private: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
 *    (IPv6 unique-local fc00::/7 counts as the same idea)
 *  - link-local: 169.254.0.0/16 (and IPv6 link-local fe80::/10)
 *  - mDNS names: anything ending in `.local`
 *
 * A public http address is refused with plain words BEFORE any
 * socket is opened — an API key or sync password must never cross
 * the open internet unencrypted just because the user pasted an
 * http address. Hostnames are never resolved to check where they
 * point (a public name that happens to resolve privately is
 * still public as far as this rule is concerned); IP literals
 * are parsed, never looked up.
 *
 * The manifest's networkSecurityConfig deliberately permits
 * cleartext at the platform level (slice E2): the platform
 * config cannot name private address ranges, so a platform
 * rule tight enough to block public http would also block the
 * home-network addresses this policy allows. This object is
 * therefore the real gate for Xreader's own HTTP clients —
 * the platform setting exists only so home-network addresses
 * are reachable at all.
 */
object LocalHttpPolicy {

    /** The one refusal sentence every surface shows. */
    const val REFUSAL_MESSAGE =
        "that address uses plain http — for safety, only your own " +
            "home network or this device may use http; public " +
            "servers need https"

    /** Why [url] may not be dialled, or null when it may. Only
     * plain-http URLs can be refused; https (and anything that
     * isn't an http URL at all) is not this policy's business. */
    fun refusalReason(url: String): String? {
        val trimmed = url.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        if (scheme != "http") return null
        val host = hostOf(trimmed)
        return if (host != null && isLocalHost(host)) {
            null
        } else {
            REFUSAL_MESSAGE
        }
    }

    /** [refusalReason] as the app's error type. */
    fun refusalFor(url: String): AppError? =
        refusalReason(url)?.let { AppError.BadFile(it) }

    /** True when [error] is a PLATFORM cleartext refusal rather
     * than one of ours: the platform stacks name "cleartext" in
     * the exception message — OkHttp throws an
     * UnknownServiceException ("CLEARTEXT communication to
     * <host> not permitted by network security policy") and the
     * HttpURLConnection stack phrases it "Cleartext HTTP traffic
     * to <host> not permitted". Since slice E2 the platform
     * config permits cleartext, so Xreader's own clients should
     * never meet one; any stack that still enforces the policy
     * is recognised here so its raw sentence can be replaced
     * with [REFUSAL_MESSAGE]. Causes are walked because stacks
     * differ in how they wrap the refusal. */
    fun isCleartextRefusal(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .take(6)
            .any {
                it.message?.contains("cleartext", ignoreCase = true) == true
            }

    /** True when [rawHost] names the user's own equipment (see
     * the class doc for the exact rules). */
    fun isLocalHost(rawHost: String): Boolean {
        var host = rawHost.trim().lowercase().trimEnd('.')
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length - 1)
        }
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.endsWith(".local")) return true
        if (host.contains(':')) return isLocalIpv6(host)
        val octets = parseIpv4(host) ?: return false
        return isLocalIpv4(octets)
    }

    /** The host of [url]: URI parsing first, then a manual
     * authority split for the names URI refuses (underscores and
     * friends return a null host there). */
    private fun hostOf(url: String): String? {
        runCatching { URI(url) }.getOrNull()?.host?.let { return it }
        val authority = url.substringAfter("://", "")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@')
        if (authority.isEmpty()) return null
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }
        return host.ifBlank { null }
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 ||
                !part.all { it.isDigit() }
            ) {
                return null
            }
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            out[index] = value
        }
        return out
    }

    private fun isLocalIpv4(octets: IntArray): Boolean {
        val a = octets[0]
        val b = octets[1]
        return a == 127 || // loopback 127.0.0.0/8
            a == 10 || // private 10.0.0.0/8
            (a == 172 && b in 16..31) || // private 172.16.0.0/12
            (a == 192 && b == 168) || // private 192.168.0.0/16
            (a == 169 && b == 254) // link-local 169.254.0.0/16
    }

    /** IPv6 literals only — [InetAddress.getByName] on a string
     * containing ':' parses the literal and never does DNS. The
     * classification reads the raw bytes: InetAddress's own
     * isSiteLocalAddress misses RFC 4193 unique-local addresses
     * (fc00::/7), which are exactly what home IPv6 networks use. */
    private fun isLocalIpv6(host: String): Boolean = runCatching {
        val bytes = InetAddress.getByName(host).address
        when (bytes.size) {
            // An IPv4-mapped literal collapses to 4 bytes.
            4 -> isLocalIpv4(IntArray(4) { bytes[it].toInt() and 0xFF })
            16 -> {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                val loopback = bytes.copyOfRange(0, 15)
                    .all { it == 0.toByte() } && bytes[15] == 1.toByte()
                val uniqueLocal = (first and 0xFE) == 0xFC // fc00::/7
                val linkLocal = first == 0xFE && (second and 0xC0) == 0x80 // fe80::/10
                val siteLocal = first == 0xFE && (second and 0xC0) == 0xC0 // fec0::/10
                loopback || uniqueLocal || linkLocal || siteLocal
            }
            else -> false
        }
    }.getOrDefault(false)
}
