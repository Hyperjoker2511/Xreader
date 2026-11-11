/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * FIX-18 (v1.5.2): transplanted verbatim in contract from Komikku
 * (eu.kanade.tachiyomi.ui.reader.loader.PageLoader) — abstract
 * class, getPages() returning the ui model's ReaderPage, abstract
 * isLocal, loadPage / retryPage / recycle.
 */
package app.xreader.komikku.loader

import androidx.annotation.CallSuper
import app.xreader.komikku.model.ReaderPage

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    /**
     * Whether this loader has been already recycled.
     */
    var isRecycled = false
        private set

    abstract var isLocal: Boolean

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /**
     * Loads the page. May also preload other pages.
     * Progress of the page loading should be followed via [page.statusFlow].
     * [loadPage] is not currently guaranteed to complete, so it should be launched asynchronously.
     */
    open suspend fun loadPage(page: ReaderPage) {}

    /**
     * Retries the given [page] in case it failed to load. This method only makes sense when an
     * online source is used.
     */
    open fun retryPage(page: ReaderPage) {}

    /**
     * v1.5.6 (§5.4, M4): the viewer reports the ACTUAL image
     * load outcome here — a page becomes Ready only when its
     * image really loaded, never from a load key merely
     * existing. The default just stamps the state; loaders
     * may override to add bookkeeping.
     */
    open fun onImageReady(page: ReaderPage) {
        page.status = eu.kanade.tachiyomi.source.model.Page.State.Ready
    }

    /** v1.5.6 (§5.4, M4): the page's image load failed — the
     * page takes the visible Error state [retryPage] retries
     * from. */
    open fun onImageError(page: ReaderPage, error: Throwable) {
        page.status = eu.kanade.tachiyomi.source.model.Page.State.Error(error)
    }

    /**
     * v1.5.7 (Komikku full, donor EXH): prioritise [page] in the
     * loader's queue (the ExhUtils Boost action). Default: no
     * queue to prioritise in — a plain retry.
     */
    open fun boostPage(page: ReaderPage) {
        retryPage(page)
    }

    /**
     * v1.5.7: re-queue every errored page this loader knows
     * (the ExhUtils Retry-all action). Returns the count.
     */
    open fun retryAllErrored(): Int = 0

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    open fun recycle() {
        isRecycled = true
    }
}
