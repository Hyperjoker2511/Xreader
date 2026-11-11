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
 * FIX-18 (v1.5.2): transplanted from Komikku
 * (eu.kanade.tachiyomi.ui.reader.model.ReaderPage / ReaderChapter /
 * ViewerChapters). The donor's Page base is Xreader's own :mihon-api
 * transplant of the same class, so ReaderPage extends it unchanged.
 * ReaderChapter keeps the donor's state machine, pageLoader slot,
 * requestedPage and ref/unref recycling; its wrapped chapter is
 * Xreader's (id, title, lastPageRead, read) instead of the donor's
 * SQLDelight Chapter. The seam fields Xreader's pipeline adds to a
 * page (localFile, referer) sit below the donor's own fields.
 */
package app.xreader.komikku.model

import android.net.Uri
import app.xreader.komikku.loader.PageLoader
import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    // v1.5.12 (M3, Lane 6 AT-RISK 1): the donor Page's uri,
    // carried through instead of dropped — an extension whose
    // getImageUrl/getImage reads page.uri (the deprecated-but-
    // live field) must find it on the loader road too. The
    // donor ReaderPage passes its Page's uri to the same super
    // slot; pre-v1.5.12 Xreader hardcoded null here.
    uri: Uri? = null,
    // SY -->
    /** Value to check if this page is used to as if it was too wide */
    var shiftedPage: Boolean = false,
    /** Value to check if a page is can be doubled up, but can't because the next page is too wide */
    var isolatedPage: Boolean = false,
    // SY <--
    var stream: (() -> InputStream)? = null
) : Page(index, url, imageUrl, uri) {

    open lateinit var chapter: ReaderChapter

    /** Value to check if a page is too wide to be doubled up */
    var fullPage: Boolean = false
        set(value) {
            field = value
            if (value) shiftedPage = false
        }

    // -- FIX-18 seam fields (Xreader pipeline, not donor) -----------

    /** The downloaded copy's file name inside the book's content
     * directory, or null while the page has only been read online.
     * The reader prefers the local file when present. */
    var localFile: String? = null

    /** The Referer the chapter's page images expect, carried from
     * the ChapterPages record (the Keiyoushi adapter stamps it). */
    var referer: String? = null

    /** The model the image pipeline should load: the local file
     * name when downloaded, else the remote image URL. */
    val loadKey: String
        get() = localFile ?: (imageUrl ?: url)

    /** v1.5.6 (§5.6): this page's pixel dimensions once its
     * image request has resolved (filled by the viewer's
     * decode paths and the ViewModel's shape probe); 0 =
     * unknown. The chapter shape verdict reads these over the
     * whole page set. */
    var imageWidth: Int = 0
    var imageHeight: Int = 0
}

/** Donor ReaderChapter: state machine + loader slot + refcounted
 * recycling, over Xreader's chapter identity. */
class ReaderChapter(
    val chapterId: Long,
    val title: String,
    val lastPageRead: Int = 0,
    val read: Boolean = false,
    /** v1.5.8 (E1/E2/I2): the chapter's scanlator, mapped by
     * the ViewModel from the Room column the extension
     * supplied. Null = the source credited nobody. */
    var scanlator: String? = null
) {

    val stateFlow = MutableStateFlow<State>(State.Wait)
    var state: State
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
        }

    val pages: List<ReaderPage>?
        get() = (state as? State.Loaded)?.pages

    var pageLoader: PageLoader? = null

    var requestedPage: Int = 0

    private var references = 0

    fun ref() {
        references++
    }

    fun unref() {
        references--
        if (references == 0) {
            pageLoader?.recycle()
            pageLoader = null
            state = State.Wait
        }
    }

    sealed interface State {
        data object Wait : State
        data object Loading : State
        data class Error(val error: Throwable) : State
        data class Loaded(val pages: List<ReaderPage>) : State
    }
}

/** Donor ViewerChapters: the prev / current / next window. */
data class ViewerChapters(
    val prevChapter: ReaderChapter?,
    val currChapter: ReaderChapter,
    val nextChapter: ReaderChapter?
)
