/*
 * Shape follows keiyoushi/extensions-lib @42255ee5 (the binary contract) exactly.
 *
 * Implementation transplanted from Komikku (Apache-2.0) in slice K6: the donor's
 * real engine, QuickJS-backed — see NOTICE. One named substitution: the donor's
 * withIOContext (tachiyomi core util) becomes withContext(Dispatchers.IO), the
 * identical dispatcher hop. K1 shipped a named stub here; the K6 survey found
 * 0 of 1,449 live index extensions referencing this class, and the engine is
 * wired anyway because the transplant is contained (this class + the quickjs
 * dependency + one Injekt registration) and the index is live — a JS-using
 * source can appear in any repo update and will now work the day it lands.
 */
package eu.kanade.tachiyomi.network

import android.content.Context
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Util for evaluating JavaScript in sources.
 */
@Suppress("UNUSED", "UNCHECKED_CAST")
class JavaScriptEngine(@Suppress("UNUSED_PARAMETER") context: Context) {

    /**
     * Evaluate arbitrary JavaScript code and get the result as a primitive type
     * (e.g., String, Int).
     *
     * @since extensions-lib 1.4
     * @param script JavaScript to execute.
     * @return Result of JavaScript code as a primitive type.
     */
    suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.IO) {
        QuickJs.create().use {
            it.evaluate(script) as T
        }
    }
}
