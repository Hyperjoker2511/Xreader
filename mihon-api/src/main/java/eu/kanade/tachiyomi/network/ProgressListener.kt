/*
 * Transplanted verbatim for Xreader from Komikku (core/common, Apache-2.0) —
 * see the NOTICE file in this module for credits and donor revisions.
 */
package eu.kanade.tachiyomi.network

interface ProgressListener {
    fun update(bytesRead: Long, contentLength: Long, done: Boolean)
}
