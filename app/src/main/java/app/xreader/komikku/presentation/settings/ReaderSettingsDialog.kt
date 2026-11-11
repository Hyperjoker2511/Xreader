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
 * (eu.kanade.presentation.reader.settings.ReaderSettingsDialog +
 * ReadingModePage + GeneralSettingsPage + ColorFilterPage) — the
 * same three tabs, the same items in the same order, over the
 * transplanted ReaderPreferences. Scope (owner's rule): the
 * per-series rows (reading mode, orientation) write the series
 * flags; every other row writes through the SCOPED preferences —
 * inside the reader that is this book's layer, never the global
 * default.
 */
package app.xreader.komikku.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.komikku.KomikkuReaderViewModel
import app.xreader.komikku.setting.KPreference
import app.xreader.komikku.setting.ReaderOrientation
import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.setting.ReadingMode

// -- Shared rows (the donor's presentation-core components) -------

@Composable
fun KomikkuCheckboxItem(label: String, pref: KPreference<Boolean>) {
    val checked by pref.changes().collectAsState(initial = pref.get())
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { pref.set(it) })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun KomikkuCheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KomikkuChipRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEachIndexed { index, option ->
                FilterChip(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    label = { Text(option) }
                )
            }
        }
    }
}

@Composable
fun KomikkuSliderItem(
    label: String,
    value: Int,
    valueRange: IntRange,
    valueText: String = value.toString(),
    onChange: (Int) -> Unit
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0)
        )
    }
}

@Composable
fun KomikkuHeading(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

// -- The dialog ----------------------------------------------------

/**
 * v1.5.8 (I4): the Custom filter tab's live-preview state —
 * the donor ReaderSettingsDialog behaviour as data. While the
 * filter tab (index 2) is open, the preview is ACTIVE: the
 * sheet undims and the reader chrome hides, so the filter is
 * judged on the live page. Leaving the tab (or closing the
 * sheet) clears BOTH, and the screen restores the chrome it
 * had. Pure, so the state machine is unit-tested.
 */
data class FilterPreviewState(
    val active: Boolean = false
) {
    val sheetUndimmed: Boolean get() = active
    val chromeHidden: Boolean get() = active

    fun onTabChanged(tabIndex: Int): FilterPreviewState =
        FilterPreviewState(active = tabIndex == FILTER_TAB_INDEX)

    fun onSheetClosed(): FilterPreviewState = FilterPreviewState(active = false)

    companion object {
        const val FILTER_TAB_INDEX = 2
    }
}

@Composable
fun ReaderSettingsDialog(
    viewModel: KomikkuReaderViewModel,
    onDismiss: () -> Unit,
    // v1.5.7 (R-22) + v1.5.8 (I4): the donor's live filter
    // preview — while the Custom filter tab is open the sheet
    // undims and the reader chrome hides; leaving restores
    // both (the screen owns the restore).
    onCustomFilterTabVisible: (Boolean) -> Unit = {}
) {
    var tab by remember { mutableIntStateOf(0) }
    var preview by remember {
        androidx.compose.runtime.mutableStateOf(FilterPreviewState())
    }
    androidx.compose.runtime.LaunchedEffect(tab) {
        preview = preview.onTabChanged(tab)
        onCustomFilterTabVisible(preview.active)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onCustomFilterTabVisible(false) }
    }
    // I4: undim the sheet's window while the preview is active
    // (best-effort window seam — the STATE above is the
    // contract; the window follows it).
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)
            ?.window
        if (preview.sheetUndimmed) {
            window?.setDimAmount(0f)
        } else {
            window?.setDimAmount(0.56f)
        }
    }
    val tabs = listOf("Reading mode", "General", "Colour filter")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Reader settings")
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        },
        text = {
            LazyColumn(Modifier.height(440.dp)) {
                item {
                    when (tab) {
                        0 -> ReadingModePage(viewModel)
                        1 -> GeneralPage(viewModel)
                        else -> ColorFilterPage(viewModel)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

// -- Page 1: reading mode (donor ReadingModePage) ------------------

@Composable
private fun ReadingModePage(viewModel: KomikkuReaderViewModel) {
    val prefs = viewModel.scopedPreferences
    val state by viewModel.state.collectAsState()

    KomikkuHeading("For this series")
    KomikkuChipRow(
        label = "Reading mode",
        options = ReadingMode.entries.map { it.label },
        selectedIndex = ReadingMode.entries.indexOf(state.readingMode),
        onSelect = { viewModel.onChangeReadingMode(ReadingMode.entries[it]) }
    )
    KomikkuChipRow(
        label = "Rotation",
        options = ReaderOrientation.entries.map { it.label },
        selectedIndex = ReaderOrientation.entries.indexOf(state.orientation),
        onSelect = { viewModel.onChangeOrientation(ReaderOrientation.entries[it]) }
    )

    if (state.readingMode.type == ReadingMode.ViewerType.Webtoon) {
        WebtoonViewerSettings(viewModel)
        KomikkuHeading("Continuous vertical")
        KomikkuCheckboxItem(
            "Crop borders",
            prefs.cropBordersContinuousVertical()
        )
    } else {
        PagerViewerSettings(viewModel)
    }
}

@Composable
private fun TapZonesItems(
    prefs: ReaderPreferences,
    navPref: KPreference<Int>,
    invertPref: KPreference<ReaderPreferences.TappingInvertMode>
) {
    val nav by navPref.changes().collectAsState(initial = navPref.get())
    KomikkuChipRow(
        label = "Tap zones",
        options = ReaderPreferences.TapZones,
        selectedIndex = nav,
        onSelect = { navPref.set(it) }
    )
    if (nav != 5) {
        val invert by invertPref.changes().collectAsState(initial = invertPref.get())
        KomikkuChipRow(
            label = "Invert tapping",
            options = ReaderPreferences.TappingInvertMode.entries.map { it.label },
            selectedIndex = ReaderPreferences.TappingInvertMode.entries.indexOf(invert),
            onSelect = {
                invertPref.set(ReaderPreferences.TappingInvertMode.entries[it])
            }
        )
    }
}

@Composable
private fun PagerViewerSettings(viewModel: KomikkuReaderViewModel) {
    val prefs = viewModel.scopedPreferences
    KomikkuHeading("Pager viewer")
    TapZonesItems(prefs, prefs.navigationModePager(), prefs.pagerNavInverted())

    val imageScaleType by prefs.imageScaleType().changes()
        .collectAsState(initial = prefs.imageScaleType().get())
    KomikkuChipRow(
        label = "Image scale type",
        options = ReaderPreferences.ImageScaleType,
        selectedIndex = imageScaleType - 1,
        onSelect = { prefs.imageScaleType().set(it + 1) }
    )
    val zoomStart by prefs.zoomStart().changes()
        .collectAsState(initial = prefs.zoomStart().get())
    KomikkuChipRow(
        label = "Zoom start position",
        options = ReaderPreferences.ZoomStart,
        selectedIndex = zoomStart - 1,
        onSelect = { prefs.zoomStart().set(it + 1) }
    )
    // SY -->
    val pageLayout by prefs.pageLayout().changes()
        .collectAsState(initial = prefs.pageLayout().get())
    KomikkuChipRow(
        label = "Page layout",
        options = ReaderPreferences.PageLayouts,
        selectedIndex = pageLayout,
        onSelect = { prefs.pageLayout().set(it) }
    )
    // SY <--
    // KMK -->
    KomikkuCheckboxItem("Smaller tap zones", prefs.smallerTapZone())
    // KMK <--
    KomikkuCheckboxItem("Crop borders", prefs.cropBorders())
    // KMK -->
    if (imageScaleType in ReaderPreferences.zoomWideImagesAllowedList) {
        KomikkuCheckboxItem("Zoom wide images in landscape", prefs.landscapeZoom())
    }
    // KMK <--
    KomikkuCheckboxItem("Navigate to pan", prefs.navigateToPan())

    val dualSplit by prefs.dualPageSplitPaged().changes()
        .collectAsState(initial = false)
    KomikkuCheckboxItem("Split wide pages", prefs.dualPageSplitPaged())
    if (dualSplit) {
        KomikkuCheckboxItem("Invert split pages", prefs.dualPageInvertPaged())
    }
    val dualRotate by prefs.dualPageRotateToFit().changes()
        .collectAsState(initial = false)
    KomikkuCheckboxItem("Rotate pages to fit", prefs.dualPageRotateToFit())
    if (dualRotate) {
        KomikkuCheckboxItem(
            "Invert rotated pages",
            prefs.dualPageRotateToFitInvert()
        )
    }
    // SY -->
    KomikkuCheckboxItem("Page transitions", prefs.pageTransitionsPager())
    KomikkuCheckboxItem("Invert double pages", prefs.invertDoublePages())
    // KMK -->
    val pagedDisableZoomIn by prefs.pagedDisableZoomIn().changes()
        .collectAsState(initial = false)
    KomikkuCheckboxItem("Disable zoom in", prefs.pagedDisableZoomIn())
    if (!pagedDisableZoomIn) {
        KomikkuCheckboxItem("Double tap to zoom", prefs.pagedDoubleTapZoomEnabled())
    }
    // KMK <--
    val centerMargin by prefs.centerMarginType().changes()
        .collectAsState(initial = prefs.centerMarginType().get())
    KomikkuChipRow(
        label = "Centre margin",
        options = ReaderPreferences.CenterMarginTypes,
        selectedIndex = centerMargin,
        onSelect = { prefs.centerMarginType().set(it) }
    )
    // SY <--
}

@Composable
private fun WebtoonViewerSettings(viewModel: KomikkuReaderViewModel) {
    val prefs = viewModel.scopedPreferences
    val state by viewModel.state.collectAsState()
    KomikkuHeading("Webtoon viewer")
    TapZonesItems(prefs, prefs.navigationModeWebtoon(), prefs.webtoonNavInverted())

    // KMK -->
    val scaleTypePref = prefs.webtoonScaleType()
    val scaleType by scaleTypePref.changes().collectAsState(initial = scaleTypePref.get())
    val smartScale by prefs.longStripGapSmartScale().changes()
        .collectAsState(initial = false)
    if (state.readingMode != ReadingMode.CONTINUOUS_VERTICAL || smartScale) {
        KomikkuChipRow(
            label = "Webtoon scale type",
            options = ReaderPreferences.WebtoonScaleType.entries.map { it.label },
            selectedIndex = ReaderPreferences.WebtoonScaleType.entries.indexOf(scaleType),
            onSelect = {
                scaleTypePref.set(ReaderPreferences.WebtoonScaleType.entries[it])
            }
        )
    }
    // KMK <--
    val sidePadding by prefs.webtoonSidePadding().changes()
        .collectAsState(initial = prefs.webtoonSidePadding().get())
    KomikkuSliderItem(
        label = "Side padding",
        value = sidePadding,
        valueRange = ReaderPreferences.WEBTOON_PADDING_MIN..ReaderPreferences.WEBTOON_PADDING_MAX,
        valueText = "${sidePadding}%",
        onChange = { prefs.webtoonSidePadding().set(it) }
    )
    // KMK -->
    KomikkuCheckboxItem("Smaller tap zones", prefs.smallerTapZone())
    // KMK <--
    KomikkuCheckboxItem("Crop borders", prefs.cropBordersWebtoon())
    // SY -->
    KomikkuCheckboxItem("Smooth auto scroll", prefs.smoothAutoScroll())
    KomikkuCheckboxItem("Page transitions", prefs.pageTransitionsWebtoon())
    // SY <--
    val dualSplit by prefs.dualPageSplitWebtoon().changes()
        .collectAsState(initial = false)
    KomikkuCheckboxItem("Split wide pages", prefs.dualPageSplitWebtoon())
    if (dualSplit) {
        KomikkuCheckboxItem("Invert split pages", prefs.dualPageInvertWebtoon())
    }
    val dualRotate by prefs.dualPageRotateToFitWebtoon().changes()
        .collectAsState(initial = false)
    KomikkuCheckboxItem("Rotate pages to fit", prefs.dualPageRotateToFitWebtoon())
    if (dualRotate) {
        KomikkuCheckboxItem(
            "Invert rotated pages",
            prefs.dualPageRotateToFitInvertWebtoon()
        )
    }
    KomikkuCheckboxItem("Double tap to zoom", prefs.webtoonDoubleTapZoomEnabled())
    // KMK -->
    KomikkuCheckboxItem("Pinch to zoom", prefs.webtoonPinchToZoomEnabled())
    // KMK <--
    KomikkuCheckboxItem("Disable zoom out", prefs.webtoonDisableZoomOut())
}

// -- Page 2: general (donor GeneralSettingsPage) -------------------

@Composable
private fun GeneralPage(viewModel: KomikkuReaderViewModel) {
    val prefs = viewModel.scopedPreferences
    val readerTheme by prefs.readerTheme().changes()
        .collectAsState(initial = prefs.readerTheme().get())
    KomikkuChipRow(
        label = "Reader theme",
        options = listOf("Black", "Grey", "White", "Automatic"),
        selectedIndex = when (readerTheme) {
            1 -> 0
            2 -> 1
            0 -> 2
            else -> 3
        },
        onSelect = {
            prefs.readerTheme().set(listOf(1, 2, 0, 3)[it])
        }
    )
    KomikkuCheckboxItem("Show page number", prefs.showPageNumber())
    // SY -->
    val forceHorz by prefs.forceHorizontalSeekbar().changes()
        .collectAsState(initial = false)
    KomikkuCheckboxItem("Force horizontal seek bar", prefs.forceHorizontalSeekbar())
    if (!forceHorz) {
        KomikkuCheckboxItem(
            "Vertical seek bar in landscape",
            prefs.landscapeVerticalSeekbar()
        )
        KomikkuCheckboxItem(
            "Left-handed vertical seek bar",
            prefs.leftVerticalSeekbar()
        )
    }
    // SY <--
    val fullscreen by prefs.fullscreen().changes().collectAsState(initial = true)
    KomikkuCheckboxItem("Fullscreen", prefs.fullscreen())
    if (fullscreen) {
        KomikkuCheckboxItem("Draw under cutout", prefs.drawUnderCutout())
    }
    KomikkuCheckboxItem("Keep screen on", prefs.keepScreenOn())
    KomikkuCheckboxItem("Read with long tap", prefs.readWithLongTap())
    KomikkuCheckboxItem(
        "Always show chapter transition",
        prefs.alwaysShowChapterTransition()
    )
    val flashOn by prefs.flashOnPageChange().changes().collectAsState(initial = false)
    KomikkuCheckboxItem("Flash on page change", prefs.flashOnPageChange())
    if (flashOn) {
        val flashMillis by prefs.flashDurationMillis().changes()
            .collectAsState(initial = prefs.flashDurationMillis().get())
        KomikkuSliderItem(
            label = "Flash duration",
            value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
            valueRange = 1..15,
            valueText = "${flashMillis}ms",
            onChange = {
                prefs.flashDurationMillis().set(it * ReaderPreferences.MILLI_CONVERSION)
            }
        )
        val flashInterval by prefs.flashPageInterval().changes()
            .collectAsState(initial = prefs.flashPageInterval().get())
        KomikkuSliderItem(
            label = "Flash every N pages",
            value = flashInterval,
            valueRange = 1..10,
            onChange = { prefs.flashPageInterval().set(it) }
        )
        val flashColor by prefs.flashColor().changes()
            .collectAsState(initial = prefs.flashColor().get())
        KomikkuChipRow(
            label = "Flash with",
            options = listOf("Black", "White", "White and black"),
            selectedIndex = ReaderPreferences.FlashColor.entries.indexOf(flashColor),
            onSelect = {
                prefs.flashColor().set(ReaderPreferences.FlashColor.entries[it])
            }
        )
    }
    // SY -->
    KomikkuCheckboxItem("Auto webtoon mode", prefs.useAutoWebtoon())
    // SY <--
}

// -- Page 3: colour filter (donor ColorFilterPage) -----------------

@Composable
private fun ColorFilterPage(viewModel: KomikkuReaderViewModel) {
    val prefs = viewModel.scopedPreferences
    val customBrightness by prefs.customBrightness().changes()
        .collectAsState(initial = false)
    KomikkuCheckboxItem("Custom brightness", prefs.customBrightness())
    if (customBrightness) {
        val value by prefs.customBrightnessValue().changes()
            .collectAsState(initial = prefs.customBrightnessValue().get())
        KomikkuSliderItem(
            label = "Brightness",
            value = value,
            valueRange = -75..100,
            onChange = { prefs.customBrightnessValue().set(it) }
        )
    }
    val colorFilter by prefs.colorFilter().changes().collectAsState(initial = false)
    KomikkuCheckboxItem("Custom colour filter", prefs.colorFilter())
    if (colorFilter) {
        val filterValue by prefs.colorFilterValue().changes()
            .collectAsState(initial = prefs.colorFilterValue().get())
        ChannelSlider(prefs, "Red", 16, filterValue)
        ChannelSlider(prefs, "Green", 8, filterValue)
        ChannelSlider(prefs, "Blue", 0, filterValue)
        ChannelSlider(prefs, "Alpha", 24, filterValue)
        val mode by prefs.colorFilterMode().changes()
            .collectAsState(initial = prefs.colorFilterMode().get())
        KomikkuChipRow(
            label = "Filter mode",
            options = ReaderPreferences.ColorFilterMode.map { it.first },
            selectedIndex = mode,
            onSelect = { prefs.colorFilterMode().set(it) }
        )
    }
    KomikkuCheckboxItem("Greyscale", prefs.grayscale())
    KomikkuCheckboxItem("Inverted colours", prefs.invertedColors())
}

/** One ARGB channel of the donor's colour filter (its
 * getColorValue mask math, preserved). */
@Composable
private fun ChannelSlider(
    prefs: ReaderPreferences,
    label: String,
    shift: Int,
    filterValue: Int
) {
    val channel = (filterValue shr shift) and 0xFF
    KomikkuSliderItem(
        label = label,
        value = channel,
        valueRange = 0..255,
        onChange = { newValue ->
            val mask = (0xFF shl shift).inv()
            prefs.colorFilterValue().set(
                (newValue shl shift) or (filterValue and mask)
            )
        }
    )
}
