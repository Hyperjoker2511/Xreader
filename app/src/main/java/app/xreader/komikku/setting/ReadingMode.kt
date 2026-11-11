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
 * (eu.kanade.tachiyomi.ui.reader.setting.ReadingMode /
 * ReaderOrientation / ReaderBottomButton) — flag values and masks
 * preserved exactly (they are the per-series stored format). The
 * donor's Viewer factory (which builds Android Views) is replaced
 * by the transplant's Compose mode resolution in
 * app.xreader.komikku.KomikkuReaderScreen.
 */
package app.xreader.komikku.setting

import android.content.pm.ActivityInfo

enum class ReadingMode(
    val label: String,
    val flagValue: Int,
    val direction: Direction? = null,
    val type: ViewerType? = null
) {
    DEFAULT("Default", 0x00000000),
    LEFT_TO_RIGHT("Left to right", 0x00000001, Direction.Horizontal, ViewerType.Pager),
    RIGHT_TO_LEFT("Right to left", 0x00000002, Direction.Horizontal, ViewerType.Pager),
    VERTICAL("Vertical", 0x00000003, Direction.Vertical, ViewerType.Pager),
    WEBTOON("Webtoon", 0x00000004, Direction.Vertical, ViewerType.Webtoon),
    CONTINUOUS_VERTICAL("Continuous vertical", 0x00000005, Direction.Vertical, ViewerType.Webtoon);

    companion object {
        const val MASK = 0x00000007

        fun fromPreference(preference: Int?): ReadingMode =
            entries.find { it.flagValue == preference } ?: DEFAULT

        fun isPagerType(preference: Int): Boolean =
            fromPreference(preference).type is ViewerType.Pager

        /** Resolves DEFAULT against the global default preference. */
        fun resolve(preference: Int?, globalDefault: Int): ReadingMode {
            val mode = fromPreference(preference)
            return if (mode == DEFAULT) fromPreference(globalDefault) else mode
        }
    }

    sealed interface Direction {
        data object Horizontal : Direction
        data object Vertical : Direction
    }

    sealed interface ViewerType {
        data object Pager : ViewerType
        data object Webtoon : ViewerType
    }
}

enum class ReaderOrientation(
    val flag: Int,
    val label: String,
    val flagValue: Int
) {
    DEFAULT(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, "Default", 0x00000000),
    FREE(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, "Free", 0x00000008),
    PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, "Portrait", 0x00000010),
    LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, "Landscape", 0x00000018),
    LOCKED_PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Force portrait", 0x00000020),
    LOCKED_LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "Force landscape", 0x00000028),
    REVERSE_PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, "Reverse portrait", 0x00000030);

    companion object {
        const val MASK = 0x00000038

        fun fromPreference(preference: Int?): ReaderOrientation =
            entries.find { it.flagValue == preference } ?: DEFAULT

        fun resolve(preference: Int?, globalDefault: Int): ReaderOrientation {
            val orientation = fromPreference(preference)
            return if (orientation == DEFAULT) fromPreference(globalDefault) else orientation
        }
    }
}

// SY --> (transplanted from eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton)
enum class ReaderBottomButton(val value: String, val label: String) {
    ViewChapters("vc", "View chapters"),
    WebView("wb", "Open in web view"),
    Browser("br", "Open in browser"),
    Share("sh", "Share"),
    ReadingMode("rm", "Reading mode"),
    Rotation("rot", "Rotation"),
    CropBordersPager("cbp", "Crop borders"),
    CropBordersContinuesVertical("cbc", "Crop borders (continuous vertical)"),
    CropBordersWebtoon("cbw", "Crop borders (webtoon)"),
    PageLayout("pl", "Page layout");

    fun isIn(buttons: Collection<String>): Boolean = value in buttons

    companion object {
        val BUTTONS_DEFAULTS: Set<String> = setOf(
            ViewChapters,
            WebView,
            CropBordersPager,
            CropBordersContinuesVertical,
            PageLayout
        ).map { it.value }.toSet()
    }
}
