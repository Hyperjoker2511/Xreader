/*
 * Transplanted for Xreader from Komikku (core/common, Apache-2.0) with one
 * deviation named in the NOTICE file in this module: the logcat library
 * import is aliased (logcatImpl) so the delegate call below can never be
 * mis-resolved to this same-named extension under Xreader's Kotlin 2.0.21.
 */
package tachiyomi.core.common.util.system

import logcat.LogPriority
import logcat.asLog
import logcat.logcat as logcatImpl

inline fun Any.logcat(
    priority: LogPriority = LogPriority.DEBUG,
    throwable: Throwable? = null,
    tag: String? = null,
    message: () -> String = { "" },
) = logcatImpl(priority = priority) {
    val logMessage = StringBuilder()

    if (!tag.isNullOrEmpty()) {
        logMessage.append("[$tag] ")
    }

    val msg = message()
    logMessage.append(msg)

    if (throwable != null) {
        if (msg.isNotBlank()) logMessage.append("\n")
        logMessage.append(throwable.asLog())
    }

    logMessage.toString()
}
