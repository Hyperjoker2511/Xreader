package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.extensionapi.HostResponse
import app.xreader.extensionapi.Source
import app.xreader.extensionapi.SourceContext

/** A JsRuntime that never touches native code: bundles are recorded,
 * function calls are answered by a script the test supplies, and the
 * installed bridge dispatcher is exposed so tests can play the role of
 * a (possibly hostile) script. */
class FakeJsRuntime(
    private val answers: Map<String, String> = emptyMap()
) : JsRuntime {
    var bridge: ((String, String) -> String)? = null
    var bundle: String? = null
    val calls = mutableListOf<Pair<String, String>>()

    override fun installBridge(bridge: (String, String) -> String) {
        this.bridge = bridge
    }

    override fun loadBundle(script: String) {
        bundle = script
    }

    override fun callFunction(name: String, argsJson: String): String {
        calls += name to argsJson
        return answers[name] ?: "null"
    }

    override fun close() = Unit
}

/**
 * v1.0.6 (W6.2): a [RenderedPageFetcher] that never touches a
 * browser — the "rendered DOM" for each URL is whatever the test
 * supplies, and every call is recorded so routing contracts can
 * assert renders happened (or provably did NOT happen). A URL with
 * no page answers the same typed failure the real fetcher gives
 * for a page it couldn't build.
 */
class FakeRenderedFetcher(
    private val pages: Map<String, String> = emptyMap(),
    private val fallback: (String) -> String? = { null }
) : RenderedPageFetcher {

    val calls = mutableListOf<String>()

    override suspend fun fetchRendered(
        url: String,
        sessionKey: String,
        permissions: ExtensionPermissions,
        waitForSelector: String?
    ): AppResult<HostResponse> {
        calls += url
        val body = pages[url] ?: fallback(url)
            ?: return AppResult.Err(
                AppError.ProviderDown(
                    "browser view",
                    retryable = true,
                    detail = "no rendered page for $url"
                )
            )
        return AppResult.Ok(
            HostResponse(status = 200, body = body, finalUrl = url)
        )
    }
}

/** A DexLoader that pretends to load: returns one inert source. */
class FakeDexLoader : DexLoader {
    var loadCount = 0
    override fun load(
        installed: app.xreader.data.db.InstalledExtension,
        artifact: java.io.File,
        optDir: java.io.File,
        entryClass: String,
        context: SourceContext
    ): List<Source> {
        loadCount++
        return emptyList()
    }
}
