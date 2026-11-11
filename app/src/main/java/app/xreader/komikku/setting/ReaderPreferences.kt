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
 * (eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences) — every
 * accessor, key and default preserved. Adaptations, storage only:
 * the donor's PreferenceStore is Xreader's KomikkuPreferenceStore,
 * and moko StringResources become plain labels.
 */
package app.xreader.komikku.setting

import android.os.Build
import androidx.compose.ui.graphics.BlendMode
import app.xreader.komikku.viewer.pager.PagerConfig

class ReaderPreferences(
    private val preferenceStore: KomikkuPreferenceStore
) {

    // region General

    // SY -->
    fun pageTransitionsPager() = preferenceStore.getBoolean("pref_enable_transitions_pager_key", true)

    fun pageTransitionsWebtoon() = preferenceStore.getBoolean("pref_enable_transitions_webtoon_key", true)
    // SY <--

    fun flashOnPageChange() = preferenceStore.getBoolean("pref_reader_flash", false)

    fun flashDurationMillis() = preferenceStore.getInt("pref_reader_flash_duration", MILLI_CONVERSION)

    fun flashPageInterval() = preferenceStore.getInt("pref_reader_flash_interval", 1)

    fun flashColor() = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    fun doubleTapAnimSpeed() = preferenceStore.getInt("pref_double_tap_anim_speed", 500)

    fun showPageNumber() = preferenceStore.getBoolean("pref_show_page_number_key", true)

    fun showReadingMode() = preferenceStore.getBoolean("pref_show_reading_mode", true)

    fun fullscreen() = preferenceStore.getBoolean("fullscreen", true)

    fun drawUnderCutout() = preferenceStore.getBoolean("cutout_short", true)

    fun keepScreenOn() = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    /**
     * v1.5.6 (§5.6): the global default reading mode, WITH its
     * explicit-set marker. Any write through this accessor IS
     * an explicit choice (the only writer is the global
     * defaults surface in Settings/Customise), so the write
     * also stamps [defaultReadingModeExplicit]. The stored
     * value's meaning is unchanged (default RIGHT_TO_LEFT when
     * never written) — the marker is what separates "the user
     * chose this" from "the shipped default", which the mode
     * resolution needs before it may let a chapter's shape
     * decide instead.
     */
    fun defaultReadingMode(): KPreference<Int> {
        val delegate = preferenceStore.getInt(
            "pref_default_reading_mode_key",
            ReadingMode.RIGHT_TO_LEFT.flagValue
        )
        return KPreference(
            read = { delegate.get() },
            write = { value ->
                delegate.set(value)
                preferenceStore.getBoolean(
                    "pref_default_reading_mode_explicit", false
                ).set(true)
            },
            flow = delegate.changes()
        )
    }

    /** v1.5.6 (§5.6): true once the global default reading
     * mode has been explicitly chosen (written through
     * [defaultReadingMode]); false while it is still the
     * shipped default nobody picked. */
    fun defaultReadingModeExplicit() = preferenceStore.getBoolean(
        "pref_default_reading_mode_explicit", false
    )

    fun defaultOrientationType() = preferenceStore.getInt(
        "pref_default_orientation_type_key",
        ReaderOrientation.FREE.flagValue
    )

    fun webtoonDoubleTapZoomEnabled() = preferenceStore.getBoolean("pref_enable_double_tap_zoom_webtoon", true)

    // KMK -->
    fun pagedDoubleTapZoomEnabled() = preferenceStore.getBoolean("pref_enable_double_tap_zoom_paged", true)

    fun webtoonPinchToZoomEnabled() = preferenceStore.getBoolean("pref_enable_pinch_to_zoom_webtoon", true)
    // KMK <--

    fun imageScaleType() = preferenceStore.getInt("pref_image_scale_type_key", 1)

    // KMK -->
    fun webtoonScaleType() = preferenceStore.getEnum("pref_webtoon_scale_type_key", WebtoonScaleType.FIT)

    fun longStripGapSmartScale() = preferenceStore.getBoolean("pref_webtoon_smart_scale_long_strip_gap", false)
    // KMK <--

    fun zoomStart() = preferenceStore.getInt("pref_zoom_start_key", 1)

    fun readerTheme() = preferenceStore.getInt("pref_reader_theme_key", 1)

    fun alwaysShowChapterTransition() = preferenceStore.getBoolean("always_show_chapter_transition", true)

    fun cropBorders() = preferenceStore.getBoolean("crop_borders", false)

    fun navigateToPan() = preferenceStore.getBoolean("navigate_pan", true)

    fun landscapeZoom() = preferenceStore.getBoolean("landscape_zoom", true)

    // KMK -->
    fun landscapeZoomType() = preferenceStore.getEnum("landscape_zoom_type", LandscapeZoomScaleType.FIT)
    // KMK <--

    fun cropBordersWebtoon() = preferenceStore.getBoolean("crop_borders_webtoon", false)

    fun webtoonSidePadding() = preferenceStore.getInt("webtoon_side_padding", WEBTOON_PADDING_MIN)

    fun readerHideThreshold() = preferenceStore.getEnum("reader_hide_threshold", ReaderHideThreshold.LOW)

    fun folderPerManga() = preferenceStore.getBoolean("create_folder_per_manga", false)

    fun skipRead() = preferenceStore.getBoolean("skip_read", false)

    fun skipFiltered() = preferenceStore.getBoolean("skip_filtered", true)

    fun skipDupe() = preferenceStore.getBoolean("skip_dupe", false)

    fun webtoonDisableZoomOut() = preferenceStore.getBoolean("webtoon_disable_zoom_out", false)

    // KMK -->
    fun pagedDisableZoomIn() = preferenceStore.getBoolean("paged_disable_zoom_in", false)
    // KMK <--

    // endregion

    // region Split two page spread

    fun dualPageSplitPaged() = preferenceStore.getBoolean("pref_dual_page_split", false)

    fun dualPageInvertPaged() = preferenceStore.getBoolean("pref_dual_page_invert", false)

    fun dualPageSplitWebtoon() = preferenceStore.getBoolean("pref_dual_page_split_webtoon", false)

    fun dualPageInvertWebtoon() = preferenceStore.getBoolean("pref_dual_page_invert_webtoon", false)

    fun dualPageRotateToFit() = preferenceStore.getBoolean("pref_dual_page_rotate", false)

    fun dualPageRotateToFitInvert() = preferenceStore.getBoolean("pref_dual_page_rotate_invert", false)

    fun dualPageRotateToFitWebtoon() = preferenceStore.getBoolean("pref_dual_page_rotate_webtoon", false)

    fun dualPageRotateToFitInvertWebtoon() = preferenceStore.getBoolean("pref_dual_page_rotate_invert_webtoon", false)

    // endregion

    // region Color filter

    fun customBrightness() = preferenceStore.getBoolean("pref_custom_brightness_key", false)

    fun customBrightnessValue() = preferenceStore.getInt("custom_brightness_value", 0)

    fun colorFilter() = preferenceStore.getBoolean("pref_color_filter_key", false)

    fun colorFilterValue() = preferenceStore.getInt("color_filter_value", 0)

    fun colorFilterMode() = preferenceStore.getInt("color_filter_mode", 0)

    fun grayscale() = preferenceStore.getBoolean("pref_grayscale", false)

    fun invertedColors() = preferenceStore.getBoolean("pref_inverted_colors", false)

    // endregion

    // region Controls

    fun readWithLongTap() = preferenceStore.getBoolean("reader_long_tap", true)

    fun readWithVolumeKeys() = preferenceStore.getBoolean("reader_volume_keys", false)

    fun readWithVolumeKeysInverted() = preferenceStore.getBoolean("reader_volume_keys_inverted", false)

    fun navigationModePager() = preferenceStore.getInt("reader_navigation_mode_pager", 0)

    fun navigationModeWebtoon() = preferenceStore.getInt("reader_navigation_mode_webtoon", 0)

    fun pagerNavInverted() = preferenceStore.getEnum("reader_tapping_inverted", TappingInvertMode.NONE)

    fun webtoonNavInverted() = preferenceStore.getEnum("reader_tapping_inverted_webtoon", TappingInvertMode.NONE)

    fun showNavigationOverlayNewUser() = preferenceStore.getBoolean("reader_navigation_overlay_new_user", true)

    fun showNavigationOverlayOnStart() = preferenceStore.getBoolean("reader_navigation_overlay_on_start", false)

    // KMK -->
    fun smallerTapZone() = preferenceStore.getBoolean("reader_navigation_smaller_tap_zone", false)
    // KMK <--

    // endregion

    // SY -->

    fun readerThreads() = preferenceStore.getInt("eh_reader_threads", 2)

    fun readerInstantRetry() = preferenceStore.getBoolean("eh_reader_instant_retry", true)

    fun aggressivePageLoading() = preferenceStore.getBoolean("eh_aggressive_page_loading", false)

    fun cacheSize() = preferenceStore.getString("eh_cache_size", "75")

    fun autoscrollInterval() = preferenceStore.getFloat("eh_util_autoscroll_interval", 3f)

    fun smoothAutoScroll() = preferenceStore.getBoolean("smooth_auto_scroll", true)

    fun preserveReadingPosition() = preferenceStore.getBoolean("eh_preserve_reading_position", false)

    fun preloadSize() = preferenceStore.getInt("eh_preload_size", 10)

    fun useAutoWebtoon() = preferenceStore.getBoolean("eh_use_auto_webtoon", true)

    fun continuousVerticalTappingByPage() = preferenceStore.getBoolean("continuous_vertical_tapping_by_page", false)

    fun cropBordersContinuousVertical() = preferenceStore.getBoolean("crop_borders_continues_vertical", false)

    fun forceHorizontalSeekbar() = preferenceStore.getBoolean("pref_force_horz_seekbar", false)

    fun landscapeVerticalSeekbar() = preferenceStore.getBoolean("pref_show_vert_seekbar_landscape", false)

    fun leftVerticalSeekbar() = preferenceStore.getBoolean("pref_left_handed_vertical_seekbar", false)

    fun readerBottomButtons() = preferenceStore.getStringSet("reader_bottom_buttons", ReaderBottomButton.BUTTONS_DEFAULTS)

    fun pageLayout() = preferenceStore.getInt("page_layout", PagerConfig.PageLayout.AUTOMATIC)

    fun invertDoublePages() = preferenceStore.getBoolean("invert_double_pages", false)

    fun centerMarginType() = preferenceStore.getInt("center_margin_type", PagerConfig.CenterMarginType.NONE)

    fun archiveReaderMode() = preferenceStore.getInt("archive_reader_mode", ArchiveReaderMode.LOAD_FROM_FILE)
    // SY <--

    enum class FlashColor {
        BLACK,
        WHITE,
        WHITE_BLACK
    }

    enum class TappingInvertMode(
        val label: String,
        val shouldInvertHorizontal: Boolean = false,
        val shouldInvertVertical: Boolean = false
    ) {
        NONE("None"),
        HORIZONTAL("Horizontal", shouldInvertHorizontal = true),
        VERTICAL("Vertical", shouldInvertVertical = true),
        BOTH("Both", shouldInvertHorizontal = true, shouldInvertVertical = true)
    }

    enum class ReaderHideThreshold(val threshold: Int) {
        HIGHEST(5),
        HIGH(13),
        LOW(31),
        LOWEST(47)
    }

    // KMK -->
    enum class LandscapeZoomScaleType(val label: String) {
        FIT("Fit screen"),
        DOUBLE("Double")
    }

    enum class WebtoonScaleType(
        val label: String,
        val ratio: Float
    ) {
        FIT("Fit screen", 0f),
        R4_3("4:3", 3f / 4f),
        R3_2("3:2", 2f / 3f),
        R16_9("16:9", 9f / 16f),
        R20_9("20:9", 9f / 20f)
    }
    // KMK <--

    object ArchiveReaderMode {
        const val LOAD_FROM_FILE = 0
        const val LOAD_INTO_MEMORY = 1
        const val CACHE_TO_DISK = 2
    }

    companion object {
        const val WEBTOON_PADDING_MIN = 0
        const val WEBTOON_PADDING_MAX = 25

        const val MILLI_CONVERSION = 100

        val TapZones = listOf(
            "Default",
            "L-shaped",
            "Kindlish",
            "Edge",
            "Left and right",
            "Disabled"
        )

        val ImageScaleType = listOf(
            "Fit screen",
            "Stretch",
            "Fit width",
            "Fit height",
            "Original size",
            "Smart fit"
        )

        val ZoomStart = listOf(
            "Automatic",
            "Left",
            "Right",
            "Center"
        )

        // KMK -->
        // Donor: SubsamplingScaleImageView SCALE_TYPE_CENTER_INSIDE /
        // SCALE_TYPE_ORIGINAL_SIZE, compared against the imageScaleType
        // preference int — the matching preference values by name are
        // 1 (Fit screen) and 5 (Original size).
        val zoomWideImagesAllowedList = listOf(1, 5)
        // KMK <--

        val ColorFilterMode = buildList {
            addAll(
                listOf(
                    "Default" to BlendMode.SrcOver,
                    "Multiply" to BlendMode.Modulate,
                    "Screen" to BlendMode.Screen
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAll(
                    listOf(
                        "Overlay" to BlendMode.Overlay,
                        "Lighten" to BlendMode.Lighten,
                        "Darken" to BlendMode.Darken
                    )
                )
            }
        }

        // SY -->
        val PageLayouts = listOf(
            "Single page",
            "Double pages",
            "Automatic (by orientation)"
        )

        val CenterMarginTypes = listOf(
            "None",
            "Double page",
            "Wide page",
            "Double and wide page"
        )

        val archiveModeTypes = listOf(
            "Load from file",
            "Load into memory",
            "Cache to disk"
        )
        // SY <--
    }
}
