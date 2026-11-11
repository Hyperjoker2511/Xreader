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
 * v1.5.35 (item 3): the donor's webtoon layout manager
 * (Komikku's androidx.recyclerview.widget.WebtoonLayoutManager),
 * behaviour copied: item prefetch is DISABLED because the
 * extra layout space does the work instead — holders are
 * laid out (and so bound, and their images set) up to
 * [extraLayoutSpacePx] beyond the viewport, "allowing
 * setting the image even if the holder is not visible,
 * avoiding (in most cases) black views when they are
 * visible" (the donor's own words). Our v1.5.21 proof's
 * ~2 s black at scroll/turn boundaries was the plain
 * LinearLayoutManager binding pages only as they entered.
 * The donor's findLastEndVisibleItemPosition (a
 * package-private support-library hook) is not reproduced:
 * our scroll tracking reads positions from the standard
 * finders.
 */
package app.xreader.komikku.viewer.webtoon

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The webtoon viewer's layout manager: extra layout
 * space ahead of the viewport, item prefetch off. */
class WebtoonLayoutManager(
    context: Context,
    private val extraLayoutSpacePx: Int
) : LinearLayoutManager(context, VERTICAL, false) {

    init {
        isItemPrefetchEnabled = false
    }

    @Deprecated("Deprecated in Java")
    override fun getExtraLayoutSpace(state: RecyclerView.State): Int =
        extraLayoutSpacePx
}
