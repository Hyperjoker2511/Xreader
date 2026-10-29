package app.xreader.data.extension

import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The JS sandbox contract (PLAN §5/§9.1 gate). QuickJS native code
 * can't run on this JVM, so what's pinned here is everything the
 * script can actually reach: the bridge dispatcher and the source
 * adapter, driven by a fake runtime. A HOSTILE script's calls are
 * played by hand — every escape attempt must fail closed:
 * non-whitelisted hosts, unknown host methods (fs/android/eval),
 * another extension's store, and calls past the deadline.
 */
class JsBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bridgeFor(
        filesDir: java.io.File,
        packageId: String = "test.pkg",
        elapsed: () -> Long = { 0 }
    ): JsBridge {
        val transport = FixtureSite.transport()
        val context = testContext(transport, filesDir)
        val store = ExtensionStore(filesDir)
        return JsBridge(
            http = context.http,
            html = context.html,
            store = HostStoreImpl(store, packageId),
            log = context.log,
            elapsedMs = elapsed
        )
    }

    @Test
    fun `host get returns status body and final url as json`() {
        val bridge = bridgeFor(tmp.newFolder())
        val result = bridge.call(
            "http.get", """{"url":"${FixtureSite.BOOK_URL}"}"""
        )
        assertTrue(result.contains("\"status\":200"))
        assertTrue(result.contains("Sword Saga"))
    }

    @Test
    fun `html select returns element text href and src`() {
        val bridge = bridgeFor(tmp.newFolder())
        val result = bridge.call(
            "html.select",
            """{"html":"<a href='/x'>Link</a><img src='/i.png'/>","baseUrl":"https://novels.example","selector":"a"}"""
        )
        assertTrue(result.contains("Link"))
        assertTrue(result.contains("https://novels.example/x"))
    }

    @Test
    fun `hostile script cannot reach a non-whitelisted host`() {
        val bridge = bridgeFor(tmp.newFolder())
        val error = runCatching {
            bridge.call("http.get", """{"url":"https://evil.example/exfil"}""")
        }.exceptionOrNull()
        assertTrue(error is JsBridge.JsHostException)
        assertTrue(error!!.message!!.contains("blocked"))
    }

    @Test
    fun `hostile script gets no filesystem android or eval methods`() {
        val bridge = bridgeFor(tmp.newFolder())
        for (method in listOf(
            "fs.read", "fs.write", "android.open", "eval", "require",
            "http.delete", "store.list", "process.exec", ""
        )) {
            val error = runCatching { bridge.call(method, "{}") }.exceptionOrNull()
            assertTrue(
                "method $method must fail closed",
                error is JsBridge.JsHostException
            )
        }
    }

    @Test
    fun `hostile script cannot read another extensions store`() {
        val dir = tmp.newFolder()
        val mine = bridgeFor(dir, packageId = "mine.pkg")
        val other = bridgeFor(dir, packageId = "other.pkg")
        mine.call("store.put", """{"key":"secret","value":"mine"}""")
        val readBack = other.call("store.get", """{"key":"secret"}""")
        assertEquals("null", readBack)
        val ownRead = mine.call("store.get", """{"key":"secret"}""")
        assertEquals("\"mine\"", ownRead)
    }

    @Test
    fun `calls after the deadline are refused`() {
        val bridge = bridgeFor(tmp.newFolder(), elapsed = { 60_000 })
        val error = runCatching {
            bridge.call("log", """{"message":"too late"}""")
        }.exceptionOrNull()
        assertTrue(error is JsBridge.JsHostException)
        assertTrue(error!!.message!!.contains("too long"))
    }

    @Test
    fun `malformed arguments fail closed`() {
        val bridge = bridgeFor(tmp.newFolder())
        assertTrue(
            runCatching { bridge.call("http.get", "{oops") }.exceptionOrNull()
                is JsBridge.JsHostException
        )
        assertTrue(
            runCatching { bridge.call("http.get", """{"headers":{}}""") }.exceptionOrNull()
                is JsBridge.JsHostException
        )
    }

    @Test
    fun `js source adapter parses bundle results`() = runBlocking {
        val runtime = FakeJsRuntime(
            answers = mapOf(
                "popular" to """{"books":[{"id":"https://novels.example/book/sword-saga","title":"Sword Saga","author":"Jane Author"}],"hasNextPage":true}""",
                "chapters" to """[{"id":"https://novels.example/c1","name":"Chapter 1","number":1}]""",
                "chapterContent" to """{"html":"<p>Hello</p>","title":"Chapter 1"}"""
            )
        )
        val source = JsSource(
            id = "s1", name = "Scripted", lang = "en",
            baseUrl = FixtureSite.BASE, runtime = runtime
        )
        val page = source.popular(1)
        assertEquals(1, page.books.size)
        assertEquals("Sword Saga", page.books.first().title)
        assertTrue(page.hasNextPage)
        val chapters = source.chapters(page.books.first())
        assertEquals(1f, chapters.first().number)
        val content = source.chapterContent(chapters.first())
        assertEquals("<p>Hello</p>", content.html)
        // The bundle saw the invocations through the seam.
        assertTrue(runtime.calls.any { it.first == "popular" })
    }

    @Test
    fun `js source surfaces bundle failure as a source exception`() {
        val runtime = object : JsRuntime {
            override fun installBridge(bridge: (String, String) -> String) = Unit
            override fun loadBundle(script: String) = Unit
            override fun callFunction(name: String, argsJson: String): String =
                throw SourceException("bundle exploded")
            override fun close() = Unit
        }
        val source = JsSource("s1", "Scripted", "en", FixtureSite.BASE, runtime)
        try {
            source.popular(1)
            fail("expected SourceException")
        } catch (e: SourceException) {
            assertTrue(e.message!!.contains("exploded"))
        }
    }

    @Test
    fun `bootstrap defines only the host facade and invoker`() {
        assertTrue(JsBootstrap.SCRIPT.contains("globalThis.host"))
        assertTrue(JsBootstrap.SCRIPT.contains("__invoker"))
        assertFalse(JsBootstrap.SCRIPT.contains("require("))
        assertFalse(JsBootstrap.SCRIPT.contains("java"))
    }
}
