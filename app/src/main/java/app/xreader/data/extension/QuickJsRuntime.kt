package app.xreader.data.extension

import app.cash.quickjs.QuickJs
import app.xreader.extensionapi.SourceException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Java-side binding the bootstrap calls as `__hostCall.call(...)`. */
interface QuickJsHostBinding {
    fun call(method: String, argsJson: String): String
}

/** Java-side proxy over the bootstrap's `__invoker` object. */
interface QuickJsInvoker {
    fun invoke(name: String, argsJson: String): String
}

/**
 * [JsRuntime] on Cash QuickJS (Apache-2.0). DEVICE-BOUND: the native
 * engine cannot execute on the sandbox JVM, so this class is only
 * exercised on a real device; everything it orchestrates (bridge
 * dispatch, bootstrap contract, result parsing) is fixture-tested
 * through the seam.
 *
 * Containment: the bridge enforces a call deadline and hard caps on
 * result sizes, and each function call runs with a wall-clock timeout
 * on a single-thread executor — a script that ignores the deadline is
 * abandoned (its runtime is poisoned and never reused; the spinning
 * thread is a daemon). Cash QuickJS 0.9.2's facade exposes neither an
 * interrupt hook nor a memory-limit setter, so abandonment plus the
 * bridge caps are the honest mechanisms.
 */
class QuickJsRuntime(
    private val callTimeoutMs: Long = JsBridge.DEFAULT_DEADLINE_MS + 5_000
) : JsRuntime {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xreader-quickjs").apply { isDaemon = true }
    }
    private var quickJs: QuickJs? = null
    @Volatile private var poisoned = false

    override fun installBridge(bridge: (String, String) -> String) {
        val engine = ensureEngine()
        engine.set(
            "__hostCall", QuickJsHostBinding::class.java,
            object : QuickJsHostBinding {
                override fun call(method: String, argsJson: String): String =
                    bridge(method, argsJson)
            }
        )
        engine.evaluate(JsBootstrap.SCRIPT)
    }

    override fun loadBundle(script: String) {
        ensureEngine().evaluate(script)
    }

    override fun callFunction(name: String, argsJson: String): String {
        if (poisoned) {
            throw SourceException("the script was stopped earlier for running too long")
        }
        val future = executor.submit<String> {
            val invoker = ensureEngine().get("__invoker", QuickJsInvoker::class.java)
                ?: throw SourceException("the script runtime didn't start")
            invoker.invoke(name, argsJson)
        }
        return try {
            future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            poisoned = true
            future.cancel(true)
            throw SourceException("the script ran too long and was stopped")
        } catch (e: Exception) {
            val cause = e.cause ?: e
            throw SourceException(
                cause.message?.take(300) ?: "the script failed"
            )
        }
    }

    private fun ensureEngine(): QuickJs {
        quickJs?.let { return it }
        val created = QuickJs.create()
        quickJs = created
        return created
    }

    override fun close() {
        runCatching { quickJs?.close() }
        quickJs = null
        executor.shutdownNow()
    }

    companion object {
        /** Kept for the report: the facade in 0.9.2 doesn't expose the
         * setter, so this documents the intended budget only. */
        const val INTENDED_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
    }
}
