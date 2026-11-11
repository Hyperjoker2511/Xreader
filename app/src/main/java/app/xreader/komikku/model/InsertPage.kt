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
 * v1.5.7 (Komikku full): transplanted verbatim in shape from
 * Komikku (eu.kanade.tachiyomi.ui.reader.model.InsertPage) — the
 * second half of a split wide page, sharing its parent's stream.
 */
package app.xreader.komikku.model

import eu.kanade.tachiyomi.source.model.Page

/** The second half of a split wide page (dual-page split):
 * an extra page item sharing the parent's stream and chapter. */
class InsertPage(val parent: ReaderPage) :
    ReaderPage(parent.index, parent.url, parent.imageUrl, parent.uri) {

    override var chapter: ReaderChapter = parent.chapter

    init {
        status = Page.State.Ready
        stream = parent.stream
        localFile = parent.localFile
        referer = parent.referer
        imageWidth = parent.imageWidth
        imageHeight = parent.imageHeight
    }
}
