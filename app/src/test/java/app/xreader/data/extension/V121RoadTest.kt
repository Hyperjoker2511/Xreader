package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.1 (Defect 4): the ROAD the novel-open path takes.
 *
 * The user's benchmark (2026-10-31): "In QuickNovel if I open
 * a novel it takes no time" — direct fetch + parse, no render.
 * The standing product rule is automatic escalation: plain
 * road first, hidden render only as fallback. The v1.2.0 code
 * broke that order for Live sources two ways, both fixed in
 * this round: (1) ExtensionService hard-wired every LIVE row
 * to render-first (that half is a one-line policy change,
 * pinned by review, not reachable from a JVM test — the
 * fetcher-level behaviour it selects IS pinned here via the
 * needsRendering marker tests); (2) even on the plain road,
 * the fetch layer forgot which road worked — every page of a
 * shell site paid a static probe before its render.
 *
 * These tests pin the fetch layer's contract:
 *  - a server-rendered page NEVER renders (QuickNovel speed);
 *  - a shell host pays the static probe ONCE per process,
 *    then renders first (the [ShellHostMemory] road memory);
 *  - a config that PROVED it needs rendering still renders
 *    first with zero static requests (TR3, unchanged);
 *  - the policy leashes still bind a proven shell host:
 *    background work does not render at all.
 */
class V121RoadTest {

    private class FakeStaticFetcher(var body: String) : PageFetcher {
        val calls = mutableListOf<String>()
        override suspend fun get(
            url: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> {
            calls += url
            return AppResult.Ok(HostResponse(200, body, finalUrl = url))
        }

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> =
            AppResult.Ok(HostResponse(200, "", finalUrl = url))
    }

    private val shellBody = """
        <html><head><title>Home</title>
        <script type="module" src="/mobile/assets/index.js"></script>
        </head><body><div id="app-root"></div></body></html>
    """.trimIndent()

    private val realBody = """
        <html><head><title>Chapter One</title></head><body>
        <nav><a href="/a">A</a><a href="/b">B</a><a href="/c">C</a>
        <a href="/d">D</a><a href="/e">E</a></nav>
        <article><p>The road into the valley wound between two
        ridges of black stone, and the cart made slow time over
        the frozen ruts while the driver hummed an old song
        about a river that never reached the sea at all.</p>
        </article></body></html>
    """.trimIndent()

    private val renderedBody = "<html><body><p>rendered page</p></body></html>"

    private fun gate(background: Boolean = false) = RenderGate(
        masterEnabled = { true },
        tier = { PerfTier.FULL },
        userDriven = { true },
        backgroundWork = { background }
    )

    private fun escalating(
        static: FakeStaticFetcher,
        rendered: FakeRenderedFetcher,
        memory: ShellHostMemory,
        needsRendering: () -> Boolean = { false },
        gate: RenderGate = gate(),
        purpose: RenderPurpose = RenderPurpose.SOURCE
    ) = EscalatingPageFetcher(
        staticFetcher = static,
        renderedFetcher = rendered,
        sessionKey = "v121-road",
        purpose = purpose,
        needsRendering = needsRendering,
        permissions = { ExtensionPermissions(domains = listOf(HOST)) },
        rateLimiter = RateLimiter(minIntervalMs = 0),
        gate = gate,
        shellHosts = memory
    )

    @Test
    fun `server rendered host never renders`() = runBlocking {
        val memory = ShellHostMemory()
        val static = FakeStaticFetcher(realBody)
        val rendered = FakeRenderedFetcher(fallback = { renderedBody })
        val first = escalating(static, rendered, memory)
        val second = escalating(static, rendered, memory)
        first.get("https://$HOST/mobile/book/101")
        second.get("https://$HOST/mobile/book/102")
        assertEquals(2, static.calls.size)
        assertTrue(rendered.calls.isEmpty())
    }

    @Test
    fun `shell host pays the static probe once per process`() = runBlocking {
        val memory = ShellHostMemory()
        val static = FakeStaticFetcher(shellBody)
        val rendered = FakeRenderedFetcher(fallback = { renderedBody })
        // First fetch on a cold memory: static probe, shell
        // detected, escalate to the render.
        val first = escalating(static, rendered, memory)
        val opened = first.get("https://$HOST/mobile/book/101")
        assertEquals(1, static.calls.size)
        assertEquals(1, rendered.calls.size)
        assertEquals(
            renderedBody,
            (opened as AppResult.Ok).value.body
        )
        // A NEW fetcher instance (the app builds one per
        // operation) sharing the memory: the same host renders
        // FIRST — no second static probe. Pre-fix every fetch
        // paid probe + render; on device the render's settle
        // waits made the probe the least of it, but the probe
        // was pure waste on a proven shell host.
        val second = escalating(static, rendered, memory)
        second.get("https://$HOST/mobile/chapter/3001")
        assertEquals(1, static.calls.size)
        assertEquals(2, rendered.calls.size)
    }

    @Test
    fun `marked needs rendering still renders first with no static call`() =
        runBlocking {
            val memory = ShellHostMemory()
            val static = FakeStaticFetcher(shellBody)
            val rendered = FakeRenderedFetcher(fallback = { renderedBody })
            val fetcher = escalating(
                static, rendered, memory,
                needsRendering = { true }
            )
            fetcher.get("https://$HOST/mobile/book/101")
            assertTrue(static.calls.isEmpty())
            assertEquals(1, rendered.calls.size)
        }

    @Test
    fun `background work never renders even for a proven shell host`() =
        runBlocking {
            val memory = ShellHostMemory()
            memory.record("https://$HOST/mobile/")
            val static = FakeStaticFetcher(shellBody)
            val rendered = FakeRenderedFetcher(fallback = { renderedBody })
            val fetcher = escalating(
                static, rendered, memory,
                gate = gate(background = true)
            )
            val result = fetcher.get("https://$HOST/mobile/book/101")
            // Downloads and workers stay on the static road
            // (the render would be refused); the shell body is
            // returned as-is, exactly as before the road memory.
            assertTrue(rendered.calls.isEmpty())
            assertEquals(
                shellBody,
                (result as AppResult.Ok).value.body
            )
        }

    @Test
    fun `probe fetches keep the render economy on a proven shell host`() =
        runBlocking {
            // The v1.0.7 probe economy: in a plain (unforced)
            // probe pass, a book page's render is the rendered
            // retry's to spend. The road memory must not let
            // the home page's escalation walk a probe's book
            // fetch rendered-first — the probe still pays its
            // static probe and escalates per fetch, exactly as
            // before the memory existed (pinned end-to-end by
            // V107FurnitureTest's rendered-home test).
            val memory = ShellHostMemory()
            memory.record("https://$HOST/mobile/")
            val static = FakeStaticFetcher(shellBody)
            val rendered = FakeRenderedFetcher(fallback = { renderedBody })
            val probe = escalating(
                static, rendered, memory,
                purpose = RenderPurpose.PROBE
            )
            probe.get("https://$HOST/mobile/book/101")
            assertEquals(1, static.calls.size)
            assertEquals(1, rendered.calls.size)
        }

    private companion object {
        const val HOST = "www.botitranslation.com"
    }
}
