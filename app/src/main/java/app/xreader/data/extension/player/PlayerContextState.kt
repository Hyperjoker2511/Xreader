package app.xreader.data.extension.player

/**
 * K2 (Keiyoushi player): which extension is executing on this thread.
 *
 * Injekt hands every extension the SAME [android.app.Application] singleton
 * (the donor model — Komikku registers one Application), so per-extension
 * namespacing of SharedPreferences / cache dirs cannot come from the
 * instance. It comes from the call context instead: whoever invokes
 * extension code (K3's loader, K4's adapter) brackets the call with
 * [withNamespace], and [PlayerApplicationContext] reads the namespace from
 * here on every call.
 *
 * The value follows the THREAD, deliberately: extension entry points in
 * the lib-1.6 contract are ordinary (suspending) functions whose bodies
 * mostly run on the caller's thread between suspension points. A caller
 * that hops threads must re-enter [withNamespace] after the hop — K3/K4
 * own that discipline; this holder is the mechanism, named here so the
 * assumption is explicit rather than buried.
 */
object PlayerContextState {

    /** Namespace used when no extension call is in flight on the thread. */
    const val SHARED_NAMESPACE = "shared"

    private val current = ThreadLocal<String?>()

    /** The namespace in force on the calling thread. */
    fun currentNamespace(): String = current.get() ?: SHARED_NAMESPACE

    /** Run [block] with [namespace] in force on this thread, restoring
     * whatever was there before (nesting-safe). */
    fun <T> withNamespace(namespace: String, block: () -> T): T {
        val previous = current.get()
        current.set(namespace)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /**
     * K4: raw swap for coroutine `ThreadContextElement`s — set
     * [namespace] (null = unset) on this thread and return what
     * was there before, so an element can install the namespace
     * on whichever thread a coroutine segment runs on and
     * restore the previous value when the segment suspends.
     * This is the mechanism behind "re-entered after suspension
     * / thread hops" for suspend entry-point calls; the sync
     * [withNamespace] above stays the tool for blocking calls.
     */
    fun swapNamespace(namespace: String?): String? {
        val previous = current.get()
        if (namespace == null) current.remove() else current.set(namespace)
        return previous
    }
}
