package app.xreader.data.extension.player

import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.lang.reflect.Array
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

/**
 * K2 OkHttp spike harness — the executable proof behind the spike
 * DECISION (see the K2 slice report).
 *
 * Evidence gathered 2026-11-01 by diffing the actual Keiyoushi APKs
 * (Asura Scans v1.6.71, Aqua Manga v1.6.73, MangaDex v1.6.1, fetched
 * from the live index) against the real OkHttp jars:
 *
 * - Against the app's OkHttp 4.12.0 (+ Okio 3.6.0), all three APKs
 *   reference six missing types (okhttp3.CompressionInterceptor,
 *   CompressionInterceptor$DecompressionAlgorithm, okhttp3.Gzip,
 *   okhttp3.brotli.Brotli, okhttp3.brotli.BrotliInterceptor,
 *   okhttp3.zstd.Zstd) and five missing members (the three INSTANCE
 *   fields, the CompressionInterceptor constructor, and
 *   CacheControl.Builder's Duration-based maxAge/maxStale). Xref puts
 *   every one of them ON the read path: the CompressionInterceptor is
 *   built in each source's lazy `client` initializer (the first
 *   request of any kind would throw NoClassDefFoundError), and the
 *   CacheControl calls sit in the <clinit> of the shared fetch-helper
 *   classes used all over each source (first use would throw
 *   NoSuchMethodError and poison the class).
 * - Against the donor's stack (OkHttp 5.3.2 + okhttp-brotli +
 *   okhttp-zstd + Okio 3.16.4 — Komikku's pinned versions), the same
 *   diff is ZERO missing types and ZERO missing members for all three
 *   APKs. OkHttp 5.0.0/5.1.0 do NOT suffice (CompressionInterceptor
 *   and okhttp-zstd landed later), and every 5.x carries Kotlin
 *   metadata 2.2.0, so the flip pairs OkHttp 5.3.2 with Kotlin 2.2.21
 *   (+ its KSP release).
 * - A bridge/shade (option a) is impossible for the CacheControl
 *   members — a host cannot add methods to a class inside the OkHttp
 *   jar — and a per-extension failure state (option c) would fail the
 *   acceptance case itself, since the pattern is the shared template,
 *   not a minority quirk.
 *
 * DECISION: (b) app-wide upgrade to the donor's exact stack, applied
 * as gate step G-K2-1 (the named, first step of the combined gate —
 * the version flip is deliberately NOT made silently inside this
 * slice). This harness is written reflectively so it COMPILES on the
 * current 4.12 stack and fails with the gate message until the flip
 * lands; after the flip it must pass, proving Asura's client shape —
 * `network.client.newBuilder()` + CompressionInterceptor(Gzip,
 * Brotli, Zstd) — builds and runs against the chosen stack.
 */
class OkHttp5StackHarnessTest {

    @Test
    fun `the stack provides every okhttp5-only type the spike found`() {
        for (name in OKHTTP5_ONLY_TYPES) {
            classOrGateFail(name)
        }
        val durationMaxAge = CacheControl.Builder::class.java.methods.any {
            it.name.startsWith("maxAge-") &&
                it.parameterTypes.contentEquals(
                    arrayOf(Long::class.javaPrimitiveType)
                )
        }
        assertTrue(
            "CacheControl.Builder lacks the Duration-based maxAge — $GATE_STEP",
            durationMaxAge
        )
        val durationMaxStale = CacheControl.Builder::class.java.methods.any {
            it.name.startsWith("maxStale-") &&
                it.parameterTypes.contentEquals(
                    arrayOf(Long::class.javaPrimitiveType)
                )
        }
        assertTrue(
            "CacheControl.Builder lacks the Duration-based maxStale — $GATE_STEP",
            durationMaxStale
        )
    }

    @Test
    fun `asura-shaped client builds and decompresses gzip transparently`() {
        val interceptor = buildCompressionInterceptor()
        val payload = "hello from the spike harness"
        val gzipped = gzip(payload)
        val seenAcceptEncoding = AtomicReference<String?>()

        val server = MiniHttpServer(
            mapOf(
                "/gzip" to (gzipped to "gzip"),
                "/plain" to (
                    "plain-ok".toByteArray(Charsets.UTF_8) to null
                    )
            ),
            seenAcceptEncoding
        )
        try {
            // The shape Asura's lazy client initializer builds: the
            // network client, derived, plus the CompressionInterceptor
            // holding all three algorithm singletons.
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .addInterceptor(interceptor)
                .build()
            val base = "http://[::1]:${server.port}"

            client.newCall(Request.Builder().url("$base/gzip").build())
                .execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals(payload, response.body!!.string())
                }
            assertTrue(
                "the interceptor must advertise gzip, saw: ${seenAcceptEncoding.get()}",
                seenAcceptEncoding.get()?.contains("gzip") == true
            )
            client.newCall(Request.Builder().url("$base/plain").build())
                .execute().use { response ->
                    assertEquals("plain-ok", response.body!!.string())
                }
        } finally {
            server.close()
        }
    }

    /**
     * A minimal HTTP/1.1 responder on a raw socket. The original
     * harness used com.sun.net.httpserver, but jdk.httpserver is not
     * a root module on the AGP unit-test classpath, so the type does
     * not resolve there (it compiled in K2's standalone kotlinc
     * harness, which ran against the full JDK). Serving the two
     * canned routes needs no framework: read the request line and
     * headers, record Accept-Encoding, answer with a fixed body and
     * an optional Content-Encoding, close.
     */
    private class MiniHttpServer(
        private val routes: Map<String, Pair<ByteArray, String?>>,
        private val seenAcceptEncoding: AtomicReference<String?>
    ) : Closeable {
        @Volatile private var closed = false
        private val socket = ServerSocket()
        val port: Int
        private val thread: Thread

        init {
            // IPv6 loopback on purpose: in the build sandbox an
            // IPv4 loopback connection from a JVM is diverted to
            // the egress proxy and black-holed (see BUILD-ENV.md —
            // the same reason every JVM here runs with
            // preferIPv6Addresses=true). ::1 stays on this machine,
            // so the client below reaches THIS server.
            socket.bind(
                InetSocketAddress(
                    java.net.InetAddress.getByName("::1"), 0
                )
            )
            socket.soTimeout = 15_000
            port = socket.localPort
            thread = Thread {
                while (!closed) {
                    try {
                        socket.accept().use { handle(it) }
                    } catch (timeout: java.net.SocketTimeoutException) {
                        // loop re-checks `closed`
                    } catch (gone: SocketException) {
                        break
                    }
                }
            }
            thread.isDaemon = true
            thread.start()
        }

        private fun handle(conn: Socket) {
            val reader = conn.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(' ')
                .getOrNull(1)?.substringBefore('?') ?: "/"
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.startsWith("Accept-Encoding:", true)) {
                    seenAcceptEncoding.set(
                        line.substringAfter(':').trim()
                    )
                }
                line = reader.readLine()
            }
            val (body, encoding) = routes[path]
                ?: (ByteArray(0) to null)
            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Length: ${body.size}\r\n")
                if (encoding != null) {
                    append("Content-Encoding: $encoding\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            val out = conn.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }

        override fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    /** CompressionInterceptor(Gzip, Brotli, Zstd), built reflectively
     * so this file compiles against either OkHttp generation. */
    private fun buildCompressionInterceptor(): Interceptor {
        val algorithmType = classOrGateFail(
            "okhttp3.CompressionInterceptor\$DecompressionAlgorithm"
        )
        val algorithms = Array.newInstance(algorithmType, 3)
        listOf("okhttp3.Gzip", "okhttp3.brotli.Brotli", "okhttp3.zstd.Zstd")
            .forEachIndexed { index, name ->
                val singleton = classOrGateFail(name).getField("INSTANCE").get(null)
                Array.set(algorithms, index, singleton)
            }
        val interceptorType = classOrGateFail("okhttp3.CompressionInterceptor")
        val constructor = interceptorType.getConstructor(algorithms.javaClass)
        return constructor.newInstance(algorithms) as Interceptor
    }

    private fun classOrGateFail(name: String): Class<*> =
        try {
            Class.forName(name)
        } catch (missing: ClassNotFoundException) {
            throw AssertionError("$name is absent — $GATE_STEP")
        }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private companion object {
        const val GATE_STEP =
            "gate step G-K2-1 (donor-stack flip: OkHttp 5.3.2 + " +
                "okhttp-brotli + okhttp-zstd + Kotlin 2.2.21/KSP pair) " +
                "must be applied before this harness can pass"

        val OKHTTP5_ONLY_TYPES = listOf(
            "okhttp3.CompressionInterceptor",
            "okhttp3.CompressionInterceptor\$DecompressionAlgorithm",
            "okhttp3.Gzip",
            "okhttp3.brotli.Brotli",
            "okhttp3.brotli.BrotliInterceptor",
            "okhttp3.zstd.Zstd"
        )
    }
}
