package app.xreader.data.extension.player

import android.content.Intent
import app.xreader.data.extension.DiagnosticLog
import java.util.Collections

/**
 * K2 (Keiyoushi player): the pure decisions behind
 * [PlayerApplicationContext] — kept free of Android instances so the
 * naming and activity rules are unit-testable on the JVM.
 *
 * Namespacing: an extension's SharedPreferences live in a file named
 * `keiyoushi_<namespace>_<requested name>` in the APP's own prefs
 * directory (so an extension can never collide with another extension's
 * file or with the app's own preference files), and its cache lives in
 * `<app cache>/keiyoushi/<namespace>/`.
 */
object PlayerContextPolicy {

    /** Root marker for everything the player namespaces. */
    const val NAMESPACE_ROOT = "keiyoushi"

    /** The SharedPreferences file name for [requestedName] under
     * [namespace]. A null/blank request (an extension asking for the
     * default prefs) lands on a stable per-extension "default" file. */
    fun prefsFileName(namespace: String, requestedName: String?): String {
        val name = requestedName?.takeIf { it.isNotBlank() } ?: "default"
        return "${NAMESPACE_ROOT}_${sanitize(namespace)}_${sanitize(name)}"
    }

    /** The per-extension cache subdirectory name (under
     * `<app cache>/keiyoushi/`). */
    fun cacheDirName(namespace: String): String = sanitize(namespace)

    /** File-system-safe form of a namespace or preference name: package
     * names keep their dots (legal in file names, and they keep the
     * mapping readable in a fault report); anything else outside
     * [a-z0-9._-] becomes an underscore. */
    fun sanitize(raw: String): String =
        raw.lowercase().map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_'
        }.joinToString("")

    /**
     * The startActivity rule. What a source legitimately does is open a
     * web page (its "open in browser" / OAuth-style handoffs): a bare
     * ACTION_VIEW with an http(s) target and NO explicit component.
     * Everything else is refused: component intents (they name donor-app
     * activities that do not exist here — Mihon's WebViewActivity and
     * friends), non-view actions, and non-web schemes (intent://,
     * market://, file paths). Refusals are recorded by [StartActivityGate].
     */
    fun isAllowedStartActivity(
        action: String?,
        scheme: String?,
        hasComponent: Boolean
    ): Boolean =
        action == Intent.ACTION_VIEW &&
            !hasComponent &&
            (scheme == "http" || scheme == "https")
}

/**
 * The funnel every extension startActivity passes through: applies
 * [PlayerContextPolicy.isAllowedStartActivity] and records each refusal
 * (in-memory list for tests/diagnostics, and a [DiagnosticLog] entry so a
 * copied fault report shows what an extension tried to open).
 */
class StartActivityGate {

    private val _refusals =
        Collections.synchronizedList(mutableListOf<String>())

    /** Every refused startActivity so far this process, oldest first. */
    val refusals: List<String>
        get() = synchronized(_refusals) { _refusals.toList() }

    /**
     * True when the intent may leave the app. [description] is the
     * intent's own toString, recorded with the refusal.
     */
    fun allow(
        action: String?,
        scheme: String?,
        hasComponent: Boolean,
        description: String
    ): Boolean {
        if (PlayerContextPolicy.isAllowedStartActivity(action, scheme, hasComponent)) {
            return true
        }
        val record = "action=${action ?: "-"} scheme=${scheme ?: "-"} " +
            "component=$hasComponent intent=$description"
        _refusals.add(record)
        DiagnosticLog.record(TAG, "Extension startActivity refused", record)
        return false
    }

    private companion object {
        const val TAG = "player-intent"
    }
}
