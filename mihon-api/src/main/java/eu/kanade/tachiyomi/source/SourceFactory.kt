/*
 * Transplanted verbatim for Xreader from Komikku (source-api, Apache-2.0) —
 * see the NOTICE file in this module for credits and donor revisions.
 */
package eu.kanade.tachiyomi.source

/**
 * A factory for creating sources at runtime.
 */
interface SourceFactory {
    /**
     * Create a new copy of the sources
     * @return The created sources
     */
    fun createSources(): List<Source>
}
