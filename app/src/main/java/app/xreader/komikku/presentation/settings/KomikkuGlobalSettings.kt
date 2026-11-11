/*
 * Copyright 2024 The Komikku Authors
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
 * FIX-18 (v1.5.2): the GLOBAL defaults half of the transplanted
 * settings surface, hosted in Settings/Customise (option-scope
 * rule: Settings = the default for all books). These are the
 * donor ReaderPreferences rows the in-reader dialog does not
 * carry — the donor exposes them in its app-level reader
 * settings. Every write here goes to the GLOBAL store (the
 * Injekt-registered ReaderPreferences); a series' own in-reader
 * choices still win for that series only.
 */
package app.xreader.komikku.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.komikku.setting.ReaderBottomButton
import app.xreader.komikku.setting.ReaderOrientation
import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.setting.ReadingMode

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KomikkuGlobalDefaultsSection(prefs: ReaderPreferences) {
    Column {
        KomikkuHeading("Manga reader (Komikku) — defaults for all books")
        Text(
            "These are the global defaults. Changing a setting " +
                "while reading applies to that series only.",
            modifier = Modifier.padding(bottom = 4.dp)
        )

        val defaultMode by prefs.defaultReadingMode().changes()
            .collectAsState(initial = prefs.defaultReadingMode().get())
        KomikkuChipRow(
            label = "Default reading mode",
            options = ReadingMode.entries.filter { it != ReadingMode.DEFAULT }
                .map { it.label },
            selectedIndex = ReadingMode.entries
                .filter { it != ReadingMode.DEFAULT }
                .indexOf(ReadingMode.fromPreference(defaultMode)),
            onSelect = {
                prefs.defaultReadingMode().set(
                    ReadingMode.entries.filter { it != ReadingMode.DEFAULT }[it].flagValue
                )
            }
        )
        val defaultOrientation by prefs.defaultOrientationType().changes()
            .collectAsState(initial = prefs.defaultOrientationType().get())
        KomikkuChipRow(
            label = "Default rotation",
            options = ReaderOrientation.entries
                .filter { it != ReaderOrientation.DEFAULT }.map { it.label },
            selectedIndex = ReaderOrientation.entries
                .filter { it != ReaderOrientation.DEFAULT }
                .indexOf(ReaderOrientation.fromPreference(defaultOrientation)),
            onSelect = {
                prefs.defaultOrientationType().set(
                    ReaderOrientation.entries
                        .filter { it != ReaderOrientation.DEFAULT }[it].flagValue
                )
            }
        )

        KomikkuCheckboxItem("Show reading mode", prefs.showReadingMode())
        KomikkuCheckboxItem("Read with volume keys", prefs.readWithVolumeKeys())
        KomikkuCheckboxItem(
            "Volume keys inverted",
            prefs.readWithVolumeKeysInverted()
        )
        val animSpeed by prefs.doubleTapAnimSpeed().changes()
            .collectAsState(initial = prefs.doubleTapAnimSpeed().get())
        KomikkuSliderItem(
            label = "Double tap animation speed",
            value = animSpeed,
            valueRange = 100..1000,
            valueText = "${animSpeed}ms",
            onChange = { prefs.doubleTapAnimSpeed().set(it) }
        )
        // v1.5.8 (K7 census): "Reader hide threshold" REMOVED —
        // dead row: Xreader's chrome toggles by tap; no
        // scroll-hide consumer exists for the pref. The pref
        // stays in the donor store; no visible control claims
        // a behaviour the app doesn't have.

        KomikkuHeading("Chapters")
        KomikkuCheckboxItem("Skip read chapters", prefs.skipRead())
        KomikkuCheckboxItem("Skip filtered chapters", prefs.skipFiltered())
        KomikkuCheckboxItem("Skip duplicate chapters", prefs.skipDupe())
        KomikkuCheckboxItem(
            "Preserve reading position",
            prefs.preserveReadingPosition()
        )
        KomikkuCheckboxItem(
            "Continuous vertical tapping by page",
            prefs.continuousVerticalTappingByPage()
        )
        // KMK -->
        KomikkuCheckboxItem(
            "Smart scale for long strip gaps",
            prefs.longStripGapSmartScale()
        )
        // KMK <--

        KomikkuHeading("Downloads and loading")
        // v1.5.8 (K7 census): "Folder per manga" REMOVED — dead
        // row: Xreader's downloads always live per book
        // (content/<bookId>/); there is no second layout for
        // the switch to choose.
        KomikkuCheckboxItem("Instant retry", prefs.readerInstantRetry())
        KomikkuCheckboxItem(
            "Aggressive page loading",
            prefs.aggressivePageLoading()
        )
        val preload by prefs.preloadSize().changes()
            .collectAsState(initial = prefs.preloadSize().get())
        KomikkuSliderItem(
            label = "Preload size",
            value = preload,
            valueRange = 1..20,
            onChange = { prefs.preloadSize().set(it) }
        )
        val threads by prefs.readerThreads().changes()
            .collectAsState(initial = prefs.readerThreads().get())
        KomikkuSliderItem(
            label = "Reader threads",
            value = threads,
            valueRange = 1..8,
            onChange = { prefs.readerThreads().set(it) }
        )
        val cacheSize by prefs.cacheSize().changes()
            .collectAsState(initial = prefs.cacheSize().get())
        KomikkuSliderItem(
            label = "Reader cache size (MB)",
            value = cacheSize.toIntOrNull() ?: 75,
            valueRange = 25..200,
            valueText = "${cacheSize}MB",
            onChange = { prefs.cacheSize().set(it.toString()) }
        )
        // v1.5.8 (K7 census): "Archive reader mode" REMOVED —
        // dead row: Xreader's local pipeline always loads
        // archive pages from file; the memory/cache variants
        // have no engine here to select.
        val autoScroll by prefs.autoscrollInterval().changes()
            .collectAsState(initial = prefs.autoscrollInterval().get())
        KomikkuSliderItem(
            label = "Auto scroll interval",
            value = autoScroll.toInt(),
            valueRange = 1..10,
            valueText = "${autoScroll}s",
            onChange = { prefs.autoscrollInterval().set(it.toFloat()) }
        )

        KomikkuHeading("Reader bottom bar buttons")
        val buttons by prefs.readerBottomButtons().changes()
            .collectAsState(initial = prefs.readerBottomButtons().get())
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReaderBottomButton.entries.forEach { button ->
                FilterChip(
                    selected = button.isIn(buttons),
                    onClick = {
                        prefs.readerBottomButtons().set(
                            if (button.isIn(buttons)) buttons - button.value
                            else buttons + button.value
                        )
                    },
                    label = { Text(button.label) }
                )
            }
        }
    }
}
