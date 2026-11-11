/*
 * Transplanted for Xreader from the keiyoushi extensions-lib
 * NovelSource contract (Apache-2.0) — see the NOTICE file in
 * this module for credits and donor revisions.
 *
 * v1.5.8 (B4): the host half of the novel-extension contract.
 * Until this type existed in the module, a Keiyoushi NOVEL
 * extension was indistinguishable from a manga one at the
 * adapter seam — every player source was stamped MANGA and
 * routed to the page reader, whose page-list leg a novel
 * extension cannot serve (its real road is [fetchPageText]).
 */
package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Page

/**
 * A marker interface for sources that provide novels.
 * Sources implementing this interface should return text
 * content in their page lists.
 */
interface NovelSource {
    /**
     * Whether this source provides novel (text-based) content.
     * Always true for NovelSource implementations.
     * This property is used by the host to determine how to
     * load a chapter's pages.
     */
    val isNovelSource: Boolean
        get() = true

    /**
     * Fetches the text content for a page.
     * @param page The page to fetch text for
     * @return The text content of the page
     */
    suspend fun fetchPageText(page: Page): String
}
