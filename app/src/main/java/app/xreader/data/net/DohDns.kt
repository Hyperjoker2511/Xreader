package app.xreader.data.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * v1.7 Patch 1 (item 5): DNS-over-HTTPS for source traffic.
 *
 * One toggle, one resolver: with the toggle ON, every
 * source HTTP client resolves hostnames through Cloudflare's
 * DNS-over-HTTPS instead of the device's resolver, so an ISP
 * that blocks or poisons plain DNS can't starve a source.
 * With the toggle OFF the system resolver runs, byte for
 * byte as before — the switch is read per lookup (see
 * [SwitchableDns]), so flipping it needs no client rebuild
 * and takes effect on the very next lookup.
 */
object DohDns {

    /** Cloudflare's DoH endpoint, as an IP literal — the
     * resolver IS 1.1.1.1, so no bootstrap lookup is ever
     * needed to reach it. */
    const val DOH_URL = "https://1.1.1.1/dns-query"

    /** The resolver: Cloudflare DoH over a plain bootstrap
     * client (system DNS — it never resolves a hostname,
     * the URL is an IP literal). */
    fun resolver(bootstrapClient: OkHttpClient = OkHttpClient()): Dns =
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(DOH_URL.toHttpUrl())
            .build()
}

/**
 * A [Dns] that resolves through [doh] while [enabled] is
 * true and through [system] otherwise. The flag is a plain
 * volatile — the container mirrors the persisted toggle
 * into it (see AppContainer.sourceDns) — so a lookup on an
 * OkHttp thread never blocks on a DataStore read.
 */
class SwitchableDns(
    private val doh: Dns,
    private val system: Dns = Dns.SYSTEM,
    enabled: Boolean = false
) : Dns {

    @Volatile
    var enabled: Boolean = enabled

    override fun lookup(hostname: String): List<InetAddress> =
        if (enabled) doh.lookup(hostname) else system.lookup(hostname)
}
