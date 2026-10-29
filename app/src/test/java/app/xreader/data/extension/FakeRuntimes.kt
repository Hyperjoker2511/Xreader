package app.xreader.data.extension

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
