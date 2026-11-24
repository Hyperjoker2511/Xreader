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
 * Code 121 (W6, design §3.8 S32): the Komikku reader settings in
 * the Material 3 design — a MODAL BOTTOM SHEET in S16's
 * settings-list language, NOT the shipped dialog form. The
 * dialog's three pages become three group sections in one
 * scrolling sheet (Reading mode · General · Colour filter), and
 * EVERY control of ReaderSettingsDialog.kt is homed here, page
 * for page, in the same order, with the same conditionals,
 * writing to the SAME scoped preference / ViewModel call the
 * dialog writes to. Presentation only: switches for the
 * dialog's checkboxes, segmented buttons for short choice sets,
 * and a settings list row opening a radio list for long ones
 * (S16's own "font row → dialog list" pattern). The dialog
 * itself is untouched and still serves the Nocturne design.
 *
 * The live filter preview: the dialog drives it from its tab
 * state (FilterPreviewState); a one-scroll sheet has no tabs,
 * so the sheet reports the preview ACTIVE while a filter the
 * preview exists for is switched on (custom brightness or the
 * custom colour filter) and clears it on dispose — the screen's
 * save/restore contract around the callback is unchanged.
 */
package app.xreader.komikku.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.komikku.KomikkuReaderViewModel
import app.xreader.komikku.presentation.ModeSelectionDialog
import app.xreader.komikku.setting.KPreference
import app.xreader.komikku.setting.ReaderOrientation
import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.setting.ReadingMode

// -- M3 settings-list rows (S16's language) -----------------------

/** A group section header in the sheet. */
@Composable
fun M3SettingsHeading(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

/** A boolean setting as a list row: label + switch. */
@Composable
fun M3SwitchRow(label: String, pref: KPreference<Boolean>) {
    val checked by pref.changes().collectAsState(initial = pref.get())
    M3SwitchRow(label = label, checked = checked, onCheckedChange = { pref.set(it) })
}

/** A boolean setting as a list row: label + spec switch
 * (check thumb when on — Code 122, Settings V2 sheet language). */
@Composable
fun M3SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                thumbContent = if (checked) {
                    {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(
                                SwitchDefaults.IconSize
                            )
                        )
                    }
                } else {
                    null
                }
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

/**
 * A choice setting as a settings list row: the label, the
 * current value beneath it, a chevron — tapping opens the radio
 * list (the shipped [ModeSelectionDialog] form, scheme-toned
 * here). For option sets too long for segmented buttons.
 */
@Composable
fun M3PickRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                options.getOrNull(selectedIndex) ?: "",
                color = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { open = true }
    )
    if (open) {
        ModeSelectionDialog(
            title = label,
            options = options,
            selectedIndex = selectedIndex,
            onSelect = { index ->
                onSelect(index)
                open = false
            },
            onDismiss = { open = false }
        )
    }
}

/** A short choice set as segmented buttons under its label. */
@Composable
fun M3SegmentedRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor =
                            MaterialTheme.colorScheme.secondaryContainer,
                        activeContentColor =
                            MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    icon = {
                        SegmentedButtonDefaults.Icon(
                            active = selectedIndex == index
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(option, maxLines = 1)
                }
            }
        }
    }
}

/** A slider setting: label + value on one line, slider beneath. */
@Composable
fun M3SliderRow(
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

/** Code 122: one settings group as a filled tonal card —
 * the sheet sits on surfaceContainerLow, its group cards ride
 * one rung up on surfaceContainer. */
@Composable
fun M3SheetGroupCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            content()
        }
    }
}

// -- The sheet ------------------------------------------------------

/**
 * S32: the reader settings as one scrolling modal bottom sheet
 * (surfaceContainerLow, per §3.7's sheet rule). The scope line
 * heads the sheet; the three groups follow in the dialog's
 * order. [onCustomFilterTabVisible] keeps the dialog's callback
 * name and contract with the screen (see the file header).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    viewModel: KomikkuReaderViewModel,
    onDismiss: () -> Unit,
    onCustomFilterTabVisible: (Boolean) -> Unit = {}
) {
    val prefs = viewModel.scopedPreferences
    val customBrightness by prefs.customBrightness().changes()
        .collectAsState(initial = false)
    val colorFilter by prefs.colorFilter().changes()
        .collectAsState(initial = false)
    val filterPreviewActive = customBrightness || colorFilter
    LaunchedEffect(filterPreviewActive) {
        onCustomFilterTabVisible(filterPreviewActive)
    }
    DisposableEffect(Unit) {
        onDispose { onCustomFilterTabVisible(false) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            item {
                Text(
                    "Reader settings",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "For this series — these settings apply to this series only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            // Code 122 (Settings V2 sheet language): each group
            // is a tonal card one rung above the sheet
            // (sheet surfaceContainerLow → cards
            // surfaceContainer). Groups, order and every
            // setting inside them are unchanged.
            item {
                M3SheetGroupCard { M3ReadingModeGroup(viewModel) }
            }
            item {
                M3SheetGroupCard { M3GeneralGroup(viewModel) }
            }
            item {
                M3SheetGroupCard { M3ColorFilterGroup(viewModel) }
            }
            item {
                androidx.compose.foundation.layout.Spacer(
                    Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}

// -- Group 1: reading mode (the dialog's ReadingModePage) -----------

@Composable
private fun M3ReadingModeGroup(viewModel: KomikkuReaderViewModel) {
    val prefs = viewModel.scopedPreferences
    val state by viewModel.state.collectAsState()

    M3SettingsHeading("Reading mode")
    M3PickRow(
        label = "Reading mode",
        options = ReadingMode.entries.map { it.label },
        selectedIndex = ReadingMode.entries.indexOf(state.readingMode),
        onSelect = { viewModel.onChangeReadingMode(ReadingMode.entries[it]) }
    )
    M3PickRow(
        label = "Rotation",
        options = ReaderOrientation.entries.map { it.label },
        selectedIndex = ReaderOrientation.entries.indexOf(state.orientation),
        onSelect = { viewModel.onChangeOrientation(ReaderOrientation.entries[it]) }
    )

    if (state.readingMode.type == ReadingMode.ViewerType.Webtoon) {
        M3WebtoonViewerSettings(viewModel)
        M3SettingsHeading("Continuous vertical")
        M3SwitchRow(
            "Crop borders",
            prefs.cropBordersContinuousVertical()
        )
    } else {
        M3PagerViewerSettings(viewModel)
    }
}

@Composable
private fun M3TapZonesItems(
    navPref: KPreference<Int>,
    invertPref: KPreference<ReaderPreferences.TappingInvertMode>
) {
    val nav by navPref.changes().collectAsState(initial = navPref.get())
    M3PickRow(
        label = "Tap zones",
        options = ReaderPreferences.TapZones,
        selectedIndex = nav,
        onSelect = { navPref.set(it) }
    )
    if (nav != 5) {
        val invert by invertPref.changes().collectAsState(initial = invertPref.get())
        M3PickRow(
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
private fun M3PagerViewerSettings(viewModel: KomikkuReaderViewModel) {
    val prefs = viewModel.scopedPreferences
    M3SettingsHeading("Pager viewer")
    M3TapZonesItems(prefs.navigationModePager(), prefs.pagerNavInverted())

    val imageScaleType by prefs.imageScaleType().changes()
        .collectAsState(initial = prefs.imageScaleType().get())
    M3PickRow(
        label = "Image scale type",
        options = ReaderPreferences.ImageScaleType,
        selectedIndex = imageScaleType - 1,
        onSelect = { prefs.imageScaleType().set(it + 1) }
    )
    val zoomStart by prefs.zoomStart().changes()
        .collectAsState(initial = prefs.zoomStart().get())
    M3PickRow(
        label = "Zoom start position",
        options = ReaderPreferences.ZoomStart,
        selectedIndex = zoomStart - 1,
        onSelect = { prefs.zoomStart().set(it + 1) }
    )
    val pageLayout by prefs.pageLayout().changes()
        .collectAsState(initial = prefs.pageLayout().get())
    M3PickRow(
        label = "Page layout",
        options = ReaderPreferences.PageLayouts,
        selectedIndex = pageLayout,
        onSelect = { prefs.pageLayout().set(it) }
    )
    M3SwitchRow("Smaller tap zones", prefs.smallerTapZone())
    M3SwitchRow("Crop borders", prefs.cropBorders())
    if (imageScaleType in ReaderPreferences.zoomWideImagesAllowedList) {
        M3SwitchRow("Zoom wide images in landscape", prefs.landscapeZoom())
    }
    M3SwitchRow("Navigate to pan", prefs.navigateToPan())

    val dualSplit by prefs.dualPageSplitPaged().changes()
        .collectAsState(initial = false)
    M3SwitchRow("Split wide pages", prefs.dualPageSplitPaged())
    if (dualSplit) {
        M3SwitchRow("Invert split pages", prefs.dualPageInvertPaged())
    }
    val dualRotate by prefs.dualPageRotateToFit().changes()
        .collectAsState(initial = false)
    M3SwitchRow("Rotate pages to fit", prefs.dualPageRotateToFit())
    if (dualRotate) {
        M3SwitchRow(
            "Invert rotated pages",
            prefs.dualPageRotateToFitInvert()
        )
    }
    M3SwitchRow("Page transitions", prefs.pageTransitionsPager())
    M3SwitchRow("Invert double pages", prefs.invertDoublePages())
    val pagedDisableZoomIn by prefs.pagedDisableZoomIn().changes()
        .collectAsState(initial = false)
    M3SwitchRow("Disable zoom in", prefs.pagedDisableZoomIn())
    if (!pagedDisableZoomIn) {
        M3SwitchRow("Double tap to zoom", prefs.pagedDoubleTapZoomEnabled())
    }
    val centerMargin by prefs.centerMarginType().changes()
        .collectAsState(initial = prefs.centerMarginType().get())
    M3PickRow(
        label = "Centre margin",
        options = ReaderPreferences.CenterMarginTypes,
        selectedIndex = centerMargin,
        onSelect = { prefs.centerMarginType().set(it) }
    )
}

@Composable
private fun M3WebtoonViewerSettings(viewModel: KomikkuReaderViewModel) {
    val prefs = viewModel.scopedPreferences
    val state by viewModel.state.collectAsState()
    M3SettingsHeading("Webtoon viewer")
    M3TapZonesItems(prefs.navigationModeWebtoon(), prefs.webtoonNavInverted())

    val scaleTypePref = prefs.webtoonScaleType()
    val scaleType by scaleTypePref.changes().collectAsState(initial = scaleTypePref.get())
    val smartScale by prefs.longStripGapSmartScale().changes()
        .collectAsState(initial = false)
    if (state.readingMode != ReadingMode.CONTINUOUS_VERTICAL || smartScale) {
        M3PickRow(
            label = "Webtoon scale type",
            options = ReaderPreferences.WebtoonScaleType.entries.map { it.label },
            selectedIndex = ReaderPreferences.WebtoonScaleType.entries.indexOf(scaleType),
            onSelect = {
                scaleTypePref.set(ReaderPreferences.WebtoonScaleType.entries[it])
            }
        )
    }
    val sidePadding by prefs.webtoonSidePadding().changes()
        .collectAsState(initial = prefs.webtoonSidePadding().get())
    M3SliderRow(
        label = "Side padding",
        value = sidePadding,
        valueRange = ReaderPreferences.WEBTOON_PADDING_MIN..ReaderPreferences.WEBTOON_PADDING_MAX,
        valueText = "${sidePadding}%",
        onChange = { prefs.webtoonSidePadding().set(it) }
    )
    M3SwitchRow("Smaller tap zones", prefs.smallerTapZone())
    M3SwitchRow("Crop borders", prefs.cropBordersWebtoon())
    M3SwitchRow("Smooth auto scroll", prefs.smoothAutoScroll())
    M3SwitchRow("Page transitions", prefs.pageTransitionsWebtoon())
    val dualSplit by prefs.dualPageSplitWebtoon().changes()
        .collectAsState(initial = false)
    M3SwitchRow("Split wide pages", prefs.dualPageSplitWebtoon())
    if (dualSplit) {
        M3SwitchRow("Invert split pages", prefs.dualPageInvertWebtoon())
    }
    val dualRotate by prefs.dualPageRotateToFitWebtoon().changes()
        .collectAsState(initial = false)
    M3SwitchRow("Rotate pages to fit", prefs.dualPageRotateToFitWebtoon())
    if (dualRotate) {
        M3SwitchRow(
            "Invert rotated pages",
            prefs.dualPageRotateToFitInvertWebtoon()
        )
    }
    M3SwitchRow("Double tap to zoom", prefs.webtoonDoubleTapZoomEnabled())
    M3SwitchRow("Pinch to zoom", prefs.webtoonPinchToZoomEnabled())
    M3SwitchRow("Disable zoom out", prefs.webtoonDisableZoomOut())
}

// -- Group 2: general (the dialog's GeneralPage) ---------------------

@Composable
private fun M3GeneralGroup(viewModel: KomikkuReaderViewModel) {
    val prefs = viewModel.scopedPreferences
    M3SettingsHeading("General")
    val readerTheme by prefs.readerTheme().changes()
        .collectAsState(initial = prefs.readerTheme().get())
    M3SegmentedRow(
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
    M3SwitchRow("Show page number", prefs.showPageNumber())
    // Code 122 (S13/S32, audit #26): the seekbar-FORM prefs are
    // NOT homed in this M3 sheet — the fixed M3 chrome's one
    // form is the horizontal floating toolbar (the vertical
    // scrubber is a Nocturne-form surface:
    // KomikkuReaderScreen's useVerticalScrubber is !m3-gated),
    // so "Force horizontal seek bar", "Vertical seek bar in
    // landscape" and "Left-handed vertical seek bar" cannot be
    // honoured here and are hidden rather than drawn inert.
    // The Nocturne dialog (ReaderSettingsDialog) keeps all
    // three, untouched. "Show page number" stays: it is
    // honoured via the centred page indicator / page chip.
    val fullscreen by prefs.fullscreen().changes().collectAsState(initial = true)
    M3SwitchRow("Fullscreen", prefs.fullscreen())
    if (fullscreen) {
        M3SwitchRow("Draw under cutout", prefs.drawUnderCutout())
    }
    M3SwitchRow("Keep screen on", prefs.keepScreenOn())
    M3SwitchRow("Read with long tap", prefs.readWithLongTap())
    M3SwitchRow(
        "Always show chapter transition",
        prefs.alwaysShowChapterTransition()
    )
    val flashOn by prefs.flashOnPageChange().changes().collectAsState(initial = false)
    M3SwitchRow("Flash on page change", prefs.flashOnPageChange())
    if (flashOn) {
        val flashMillis by prefs.flashDurationMillis().changes()
            .collectAsState(initial = prefs.flashDurationMillis().get())
        M3SliderRow(
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
        M3SliderRow(
            label = "Flash every N pages",
            value = flashInterval,
            valueRange = 1..10,
            onChange = { prefs.flashPageInterval().set(it) }
        )
        val flashColor by prefs.flashColor().changes()
            .collectAsState(initial = prefs.flashColor().get())
        M3PickRow(
            label = "Flash with",
            options = listOf("Black", "White", "White and black"),
            selectedIndex = ReaderPreferences.FlashColor.entries.indexOf(flashColor),
            onSelect = {
                prefs.flashColor().set(ReaderPreferences.FlashColor.entries[it])
            }
        )
    }
    M3SwitchRow("Auto webtoon mode", prefs.useAutoWebtoon())
}

// -- Group 3: colour filter (the dialog's ColorFilterPage) -----------

@Composable
private fun M3ColorFilterGroup(viewModel: KomikkuReaderViewModel) {
    val prefs = viewModel.scopedPreferences
    M3SettingsHeading("Colour filter")
    val customBrightness by prefs.customBrightness().changes()
        .collectAsState(initial = false)
    M3SwitchRow("Custom brightness", prefs.customBrightness())
    if (customBrightness) {
        val value by prefs.customBrightnessValue().changes()
            .collectAsState(initial = prefs.customBrightnessValue().get())
        M3SliderRow(
            label = "Brightness",
            value = value,
            valueRange = -75..100,
            onChange = { prefs.customBrightnessValue().set(it) }
        )
    }
    val colorFilter by prefs.colorFilter().changes().collectAsState(initial = false)
    M3SwitchRow("Custom colour filter", prefs.colorFilter())
    if (colorFilter) {
        val mode by prefs.colorFilterMode().changes()
            .collectAsState(initial = prefs.colorFilterMode().get())
        M3PickRow(
            label = "Filter mode",
            options = ReaderPreferences.ColorFilterMode.map { it.first },
            selectedIndex = mode,
            onSelect = { prefs.colorFilterMode().set(it) }
        )
        val filterValue by prefs.colorFilterValue().changes()
            .collectAsState(initial = prefs.colorFilterValue().get())
        M3ChannelSlider(prefs, "Red", 16, filterValue)
        M3ChannelSlider(prefs, "Green", 8, filterValue)
        M3ChannelSlider(prefs, "Blue", 0, filterValue)
        M3ChannelSlider(prefs, "Alpha", 24, filterValue)
    }
    M3SwitchRow("Greyscale", prefs.grayscale())
    M3SwitchRow("Inverted colours", prefs.invertedColors())
}

/** One ARGB channel of the colour filter — the dialog's mask
 * math, preserved verbatim. */
@Composable
private fun M3ChannelSlider(
    prefs: ReaderPreferences,
    label: String,
    shift: Int,
    filterValue: Int
) {
    val channel = (filterValue shr shift) and 0xFF
    M3SliderRow(
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
