package app.xreader.ui.reader
import app.xreader.ui.theme.aiAccentColor
import app.xreader.ui.theme.errorTextColor
import app.xreader.ui.theme.primaryTextColor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xreader.data.db.Bookmark
import app.xreader.data.db.Chapter
import app.xreader.data.db.Highlight
import app.xreader.data.reader.OverlayPlayer
import app.xreader.data.reader.ReadAloudController
import app.xreader.domain.reader.EyeState
import app.xreader.domain.reader.PageTurnOverride
import app.xreader.domain.reader.PageTurnTier
import app.xreader.domain.reader.ReaderAlignment
import app.xreader.domain.reader.ReaderFontChoice
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderMode
import app.xreader.domain.reader.SleepMode
import app.xreader.domain.reader.Soundscape
import app.xreader.ui.common.XreaderPrimaryButton
import app.xreader.domain.reader.PagedDirection
import app.xreader.domain.reader.TapZoneLayout
import app.xreader.ui.theme.Contrast
import app.xreader.ui.theme.ReaderPalettes
import app.xreader.ui.theme.maxContrast
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Settings (PLAN §2.4 #8): every control writes through ReaderPrefs and
// re-renders the reader behind the sheet — the preview is the book itself.
// ---------------------------------------------------------------------------

@Composable
fun ReaderSettingsSheet(
    config: ReaderConfig,
    effectiveTier: PageTurnTier,
    userFonts: List<String>,
    eyeState: EyeState,
    eyeMessage: String?,
    onUpdate: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onImportFont: () -> Unit,
    /** v1.0.1: jumps to the Customise screen's reader part. */
    onOpenCustomise: () -> Unit = {},
    /** v1.0.2 (R18): "This book / All books" scope for colours &
     * text size; the rest of the sheet is global by design. */
    settingsScope: ReaderSettingsScope = ReaderSettingsScope.ALL_BOOKS,
    onScopeChange: (ReaderSettingsScope) -> Unit = {},
    hasBookOverride: Boolean = false,
    onClearBookOverride: () -> Unit = {},
    /** v1.0.2 (sweep): save a word from this chapter to
     * "Words I'm learning". */
    onSaveWord: (String) -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Reading settings", style = MaterialTheme.typography.titleLarge)

        // R18 scope: visible at the TOP of the sheet, labelled in
        // plain words — never a buried preference.
        SheetSection("Apply colours & text size to") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settingsScope == ReaderSettingsScope.ALL_BOOKS,
                    onClick = { onScopeChange(ReaderSettingsScope.ALL_BOOKS) },
                    label = { Text("All books") }
                )
                FilterChip(
                    selected = settingsScope == ReaderSettingsScope.THIS_BOOK,
                    onClick = { onScopeChange(ReaderSettingsScope.THIS_BOOK) },
                    label = { Text("This book only") }
                )
            }
            if (hasBookOverride) {
                Text(
                    "This book has its own colours/text size.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onClearBookOverride) {
                    Text("Use the global look for this book")
                }
            }
        }

        SheetSection("Reading mode") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.mode == ReaderMode.PAGED,
                    onClick = { onUpdate { it.copy(mode = ReaderMode.PAGED) } },
                    label = { Text("Pages") }
                )
                FilterChip(
                    selected = config.mode == ReaderMode.SCROLL,
                    onClick = { onUpdate { it.copy(mode = ReaderMode.SCROLL) } },
                    label = { Text("Scroll") }
                )
            }
        }

        SheetSection("Page theme") {
            // v1.0.1: REAL previews — a mini page in each palette, so
            // readability is judged by eye before choosing, not from
            // a colour chip. The effective palette (high-contrast
            // substitution applied) is what the preview renders.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ReaderPalettes) { palette ->
                    val effective = palette.maxContrast(config.highContrastText)
                    val selected = config.paletteName == palette.name
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Column(
                            Modifier
                                .width(92.dp)
                                .height(118.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(effective.background)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    onUpdate { it.copy(paletteName = palette.name) }
                                }
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                "The Moon Gate",
                                color = effective.text,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                            Text(
                                "The gate opened at dawn, and the river ran silver beneath it.",
                                color = effective.text,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 3
                            )
                            Text(
                                "Chapter 12",
                                color = effective.muted,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                        Text(
                            palette.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            SheetSwitch(
                label = "High contrast text",
                checked = config.highContrastText,
                onChange = { v -> onUpdate { it.copy(highContrastText = v) } }
            )
            Text(
                "Makes the words as bright (or dark) as the page allows, whatever theme is picked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // v1.0.1 one-tap comfort: text size + brightness live at the
        // TOP of the sheet with the theme — no scrolling to reach
        // the controls that decide whether the page can be read.
        SheetSlider(
            label = "Text size",
            valueText = "${config.fontSizeSp.roundToInt()} sp",
            value = config.fontSizeSp,
            range = 12f..34f,
            onChange = { v -> onUpdate { it.copy(fontSizeSp = v) } }
        )

        SheetSection("Brightness") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Use the phone's brightness",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = config.brightness == null,
                    onCheckedChange = { system ->
                        onUpdate { it.copy(brightness = if (system) null else 0.7f) }
                    }
                )
            }
            if (config.brightness != null) {
                Slider(
                    value = config.brightness,
                    onValueChange = { v -> onUpdate { it.copy(brightness = v) } },
                    valueRange = 0.05f..1f
                )
            }
        }

        SheetSection("Auto-scroll") {
            SheetSwitch(
                label = "Auto-scroll / auto page-turn",
                checked = config.autoScrollEnabled,
                onChange = { v -> onUpdate { it.copy(autoScrollEnabled = v) } }
            )
            Text(
                "Scroll mode drifts down on its own; paged mode turns the page for you. Any touch pauses it — tap the middle of the page to carry on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SheetSlider(
                label = "Speed — ${autoScrollSpeedLabel(config.autoScrollSpeed)}",
                valueText = "%.1f×".format(config.autoScrollSpeed),
                value = config.autoScrollSpeed,
                range = 0.25f..3f,
                onChange = { v -> onUpdate { it.copy(autoScrollSpeed = v) } }
            )
        }

        // v1.0.1 custom colour pair (QuickNovel lesson): pick your
        // own page + text colours, with a plain-words warning when
        // the pair would be hard to read.
        SheetSection("Your own colours") {
            Text(
                "Pick a page colour and a word colour. Xreader warns you if they are too close to read comfortably.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val pageChoices = listOf(
                "Black" to 0xFF000000L, "Deep blue" to 0xFF0E1524L,
                "Deep green" to 0xFF14201AL, "Brown" to 0xFF211712L,
                "Cream" to 0xFFF5EDDCL, "White" to 0xFFFFFFFFL
            )
            val textChoices = listOf(
                "White" to 0xFFFFFFFFL, "Cream" to 0xFFEFDFC8L,
                "Pale blue" to 0xFFD7E3F4, "Pale green" to 0xFFDCE8D8,
                "Black" to 0xFF000000L, "Dark brown" to 0xFF2A1D12L
            )
            Text("Page", style = MaterialTheme.typography.labelMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                pageChoices.forEach { (label, argb) ->
                    FilterChip(
                        selected = config.paletteName == "Custom" &&
                            config.customPageArgb == argb,
                        onClick = {
                            onUpdate { it.copy(paletteName = "Custom", customPageArgb = argb) }
                        },
                        label = { Text(label) }
                    )
                }
            }
            Text("Words", style = MaterialTheme.typography.labelMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                textChoices.forEach { (label, argb) ->
                    FilterChip(
                        selected = config.paletteName == "Custom" &&
                            config.customTextArgb == argb,
                        onClick = {
                            onUpdate { it.copy(paletteName = "Custom", customTextArgb = argb) }
                        },
                        label = { Text(label) }
                    )
                }
            }
            if (config.paletteName == "Custom") {
                val ratio = app.xreader.ui.theme.Contrast.ratio(
                    androidx.compose.ui.graphics.Color(config.customTextArgb.toInt()),
                    androidx.compose.ui.graphics.Color(config.customPageArgb.toInt())
                )
                if (ratio < 4.5) {
                    Text(
                        "Hard to read — too close in brightness. Pick lighter words or a darker page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = app.xreader.ui.theme.errorTextColor()
                    )
                } else {
                    Text(
                        "That pair is easy to read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SheetSection("Moving between chapters") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    app.xreader.domain.reader.ChapterTransition.CONTINUOUS to "Continuous",
                    app.xreader.domain.reader.ChapterTransition.CHAPTER_BY_CHAPTER to
                        "Chapter by chapter",
                    app.xreader.domain.reader.ChapterTransition.PULL_FOR_NEXT to
                        "Pull for next"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = config.chapterTransition == value,
                        onClick = { onUpdate { it.copy(chapterTransition = value) } },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                "Continuous flows straight into the next chapter. Chapter by chapter stops at each end. Pull for next chapter waits for a little pull at the end of the chapter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SheetSwitch(
                label = "Clock on the page",
                checked = config.showPageOverlay,
                onChange = { v -> onUpdate { it.copy(showPageOverlay = v) } }
            )
            Text(
                "Shows the time and battery quietly on the page while you read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // R18/G18: hardware-keyboard presets (tablet).
            Text(
                "Keyboard control",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    app.xreader.domain.reader.KeyboardPreset.STANDARD to "Arrows + space",
                    app.xreader.domain.reader.KeyboardPreset.PAGE_KEYS to "Page keys",
                    app.xreader.domain.reader.KeyboardPreset.OFF to "Off"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = config.keyboardPreset == value,
                        onClick = { onUpdate { it.copy(keyboardPreset = value) } },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                "On a keyboard or tablet: → / ↓ / Page Down turn " +
                    "forward, ← / ↑ / Page Up go back (Standard adds " +
                    "the space bar). Keys pause while a sheet or the " +
                    "menu is open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Sweep addendum: the reading line/ruler.
            SheetSwitch(
                label = "Reading line",
                checked = config.readingLine,
                onChange = { v -> onUpdate { it.copy(readingLine = v) } }
            )
            Text(
                "A soft line at reading height that helps your eyes " +
                    "track across long lines. Scroll modes only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SheetSection("Customise") {
            TextButton(onClick = onOpenCustomise) {
                Text("Open the Customise screen — colours, glass, layout and more")
            }
            Text(
                "Tap zones",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.tapZoneLayout == TapZoneLayout.STANDARD,
                    onClick = { onUpdate { it.copy(tapZoneLayout = TapZoneLayout.STANDARD) } },
                    label = { Text("Standard") }
                )
                FilterChip(
                    selected = config.tapZoneLayout == TapZoneLayout.REVERSED,
                    onClick = { onUpdate { it.copy(tapZoneLayout = TapZoneLayout.REVERSED) } },
                    label = { Text("Reversed") }
                )
                FilterChip(
                    selected = config.tapZoneLayout == TapZoneLayout.NINE_ZONE,
                    onClick = { onUpdate { it.copy(tapZoneLayout = TapZoneLayout.NINE_ZONE) } },
                    label = { Text("Nine-zone") }
                )
                FilterChip(
                    selected = config.tapZoneLayout == TapZoneLayout.OFF,
                    onClick = { onUpdate { it.copy(tapZoneLayout = TapZoneLayout.OFF) } },
                    label = { Text("Off") }
                )
            }
            Text(
                "Page order",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.pagedDirection == PagedDirection.LTR,
                    onClick = { onUpdate { it.copy(pagedDirection = PagedDirection.LTR) } },
                    label = { Text("Left to right") }
                )
                FilterChip(
                    selected = config.pagedDirection == PagedDirection.RTL,
                    onClick = { onUpdate { it.copy(pagedDirection = PagedDirection.RTL) } },
                    label = { Text("Right to left") }
                )
            }
        }

        SheetSection("Font") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = config.fontChoice == ReaderFontChoice.LITERATA,
                    onClick = {
                        onUpdate { it.copy(fontChoice = ReaderFontChoice.LITERATA) }
                    },
                    label = { Text("Literata") }
                )
                FilterChip(
                    selected = config.fontChoice == ReaderFontChoice.JAKARTA,
                    onClick = {
                        onUpdate { it.copy(fontChoice = ReaderFontChoice.JAKARTA) }
                    },
                    label = { Text("Jakarta") }
                )
                FilterChip(
                    selected = config.fontChoice == ReaderFontChoice.INTER,
                    onClick = {
                        onUpdate { it.copy(fontChoice = ReaderFontChoice.INTER) }
                    },
                    label = { Text("Inter") }
                )
            }
            for (fontName in userFonts) {
                FilterChip(
                    selected = config.fontChoice == ReaderFontChoice.USER &&
                        config.userFontName == fontName,
                    onClick = {
                        onUpdate {
                            it.copy(
                                fontChoice = ReaderFontChoice.USER,
                                userFontName = fontName
                            )
                        }
                    },
                    label = { Text(fontName) },
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Text(
                "Import a font (.ttf / .otf)",
                color = primaryTextColor(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable { onImportFont() }
                    .padding(top = 8.dp)
            )
        }

        SheetSlider(
            label = "Line height",
            valueText = "%.2f×".format(config.lineHeightMultiplier),
            value = config.lineHeightMultiplier,
            range = 1.1f..2.2f,
            onChange = { v -> onUpdate { it.copy(lineHeightMultiplier = v) } }
        )
        SheetSlider(
            label = "Space between paragraphs",
            valueText = "${config.paragraphSpacingDp.roundToInt()} dp",
            value = config.paragraphSpacingDp,
            range = 0f..32f,
            onChange = { v -> onUpdate { it.copy(paragraphSpacingDp = v) } }
        )
        SheetSlider(
            label = "Side margins",
            valueText = "${config.marginHorizontalDp.roundToInt()} dp",
            value = config.marginHorizontalDp,
            range = 0f..72f,
            onChange = { v -> onUpdate { it.copy(marginHorizontalDp = v) } }
        )
        SheetSlider(
            label = "Top & bottom margins",
            valueText = "${config.marginVerticalDp.roundToInt()} dp",
            value = config.marginVerticalDp,
            range = 0f..64f,
            onChange = { v -> onUpdate { it.copy(marginVerticalDp = v) } }
        )

        SheetSection("Alignment") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.alignment == ReaderAlignment.LEFT,
                    onClick = { onUpdate { it.copy(alignment = ReaderAlignment.LEFT) } },
                    label = { Text("Left") }
                )
                FilterChip(
                    selected = config.alignment == ReaderAlignment.JUSTIFY,
                    onClick = {
                        onUpdate { it.copy(alignment = ReaderAlignment.JUSTIFY) }
                    },
                    label = { Text("Justified") }
                )
            }
        }

        SheetSlider(
            label = "Warm light",
            valueText = if (config.warmLight == 0f) "Off"
            else "${(config.warmLight * 100).roundToInt()}%",
            value = config.warmLight,
            range = 0f..0.6f,
            onChange = { v -> onUpdate { it.copy(warmLight = v) } }
        )

        SheetSection("Page turns") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.turnOverride == PageTurnOverride.AUTO,
                    onClick = {
                        onUpdate { it.copy(turnOverride = PageTurnOverride.AUTO) }
                    },
                    label = { Text("Automatic") }
                )
                FilterChip(
                    selected = config.turnOverride == PageTurnOverride.FULL,
                    onClick = {
                        onUpdate { it.copy(turnOverride = PageTurnOverride.FULL) }
                    },
                    label = { Text("Curl") }
                )
                FilterChip(
                    selected = config.turnOverride == PageTurnOverride.MID,
                    onClick = {
                        onUpdate { it.copy(turnOverride = PageTurnOverride.MID) }
                    },
                    label = { Text("Gooey") }
                )
                FilterChip(
                    selected = config.turnOverride == PageTurnOverride.MINIMAL,
                    onClick = {
                        onUpdate { it.copy(turnOverride = PageTurnOverride.MINIMAL) }
                    },
                    label = { Text("Fade") }
                )
            }
            Text(
                "On this device: ${tierLabel(effectiveTier)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SheetSwitch(
            label = "Bionic reading (bold word starts)",
            checked = config.bionicReading,
            onChange = { v -> onUpdate { it.copy(bionicReading = v) } }
        )
        SheetSwitch(
            label = "Volume keys turn pages",
            checked = config.volumeKeyTurn,
            onChange = { v -> onUpdate { it.copy(volumeKeyTurn = v) } }
        )
        SheetSwitch(
            label = "Keep the screen on while reading",
            checked = config.keepScreenOn,
            onChange = { v -> onUpdate { it.copy(keepScreenOn = v) } }
        )
        SheetSwitch(
            label = "Two-page spread on wide screens",
            checked = config.twoPageSpread,
            onChange = { v -> onUpdate { it.copy(twoPageSpread = v) } }
        )
        SheetSwitch(
            label = "e-Ink mode (no animation, instant turns)",
            checked = config.eInkMode,
            onChange = { v -> onUpdate { it.copy(eInkMode = v) } }
        )

        // ── v1.0.2 (sweep): Words I'm learning ───────────────────
        SheetSection("Words I'm learning") {
            var wordText by remember { mutableStateOf("") }
            var wordSavedNote by remember { mutableStateOf(false) }
            Text(
                "Met a word you want to keep? Type it here — it's " +
                    "saved with the sentence it appears in, and waits " +
                    "for you in Me → Words I'm learning.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = wordText,
                    onValueChange = {
                        wordText = it
                        wordSavedNote = false
                    },
                    label = { Text("Word") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        onSaveWord(wordText)
                        wordText = ""
                        wordSavedNote = true
                    },
                    enabled = wordText.trim().length >= 2
                ) { Text("Save") }
            }
            if (wordSavedNote) {
                Text(
                    "Saved ✓ — review it from Me → Words I'm learning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        SheetSection("Labs — experimental") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Eye-tracking page turns — EXPERIMENTAL",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        eyeStatusText(eyeState, eyeMessage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.eyeTracking,
                    onCheckedChange = { v -> onUpdate { it.copy(eyeTracking = v) } }
                )
            }
            Text(
                "Uses the front camera on this device only — nothing is " +
                    "recorded or sent anywhere. Double-blink, nod, or hold " +
                    "your gaze down to turn a page. If it doesn't behave on " +
                    "your device, it turns itself off and says why.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Plain-words auto-scroll speed (QuickNovel lesson: label speeds
 * in words, not just numbers). */
fun autoScrollSpeedLabel(speed: Float): String = when {
    speed < 0.75f -> "Slow"
    speed <= 1.5f -> "Medium"
    else -> "Fast"
}

fun tierLabel(tier: PageTurnTier): String = when (tier) {
    PageTurnTier.FULL -> "Full page curl"
    PageTurnTier.MID -> "Gooey edge-bend"
    PageTurnTier.MINIMAL -> "Crossfade (light mode for this device)"
    PageTurnTier.EINK -> "e-Ink instant"
}

fun eyeStatusText(state: EyeState, message: String?): String = when (state) {
    EyeState.OFF -> "Off"
    EyeState.WAITING_PERMISSION -> "Waiting for camera permission…"
    EyeState.STARTING -> "Starting the camera…"
    EyeState.ACTIVE -> "On — double-blink, nod, or look down to turn"
    EyeState.UNAVAILABLE -> message ?: "Not available on this device"
    EyeState.PERMISSION_DENIED -> message ?: "Camera permission was not given"
    EyeState.FAILED -> message ?: "Stopped after an error"
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun SheetSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SheetSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------------
// Listen (PLAN §6 hybrid TTS + Media Overlay transport)
// ---------------------------------------------------------------------------

@Composable
fun ListenSheet(
    ttsState: ReadAloudController.State,
    overlayState: OverlayPlayer.State,
    hasOverlay: Boolean,
    speechRate: Float,
    onTtsPlayPause: () -> Unit,
    onTtsSkipNext: () -> Unit,
    onTtsSkipPrevious: () -> Unit,
    onOverlayPlayPause: () -> Unit,
    onOverlaySeek: (Double) -> Unit,
    onOverlaySkip: (next: Boolean) -> Unit,
    onRateChange: (Float) -> Unit,
    onSleepChange: (SleepMode) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Listen", style = MaterialTheme.typography.titleLarge)

        if (hasOverlay) {
            SheetSection("This book's own audio") {
                if (overlayState.errorMessage != null) {
                    Text(
                        overlayState.errorMessage,
                        color = errorTextColor(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = { onOverlaySkip(false) }) {
                            Icon(Icons.Filled.SkipPrevious, "Previous passage")
                        }
                        IconButton(onClick = onOverlayPlayPause) {
                            Icon(
                                if (overlayState.playing) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                                if (overlayState.playing) "Pause" else "Play",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        IconButton(onClick = { onOverlaySkip(true) }) {
                            Icon(Icons.Filled.SkipNext, "Next passage")
                        }
                        Text(
                            "${formatClock(overlayState.positionSec)} / " +
                                formatClock(overlayState.durationSec),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = overlayState.positionSec.toFloat(),
                        onValueChange = { onOverlaySeek(it.toDouble()) },
                        valueRange = 0f..maxOf(1f, overlayState.durationSec.toFloat()),
                        enabled = overlayState.prepared
                    )
                    Text(
                        "The words being read are highlighted on the page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SheetSection("Device voice") {
            if (!ttsState.available) {
                Text(
                    ttsState.unavailableMessage ?: "Speech isn't available.",
                    color = errorTextColor(),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = onTtsSkipPrevious) {
                        Icon(Icons.Filled.SkipPrevious, "Previous sentence")
                    }
                    IconButton(onClick = onTtsPlayPause) {
                        Icon(
                            if (ttsState.playing) Icons.Filled.Pause
                            else Icons.Filled.PlayArrow,
                            if (ttsState.playing) "Pause" else "Read aloud",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(onClick = onTtsSkipNext) {
                        Icon(Icons.Filled.SkipNext, "Next sentence")
                    }
                    Text(
                        if (ttsState.sentenceCount > 0) {
                            "Sentence ${ttsState.sentenceIndex + 1} of ${ttsState.sentenceCount}"
                        } else {
                            "Starts from where you are on the page"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SheetSection("Speed") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (rate in listOf(0.75f, 1f, 1.25f, 1.5f, 2f)) {
                    FilterChip(
                        selected = speechRate == rate,
                        onClick = { onRateChange(rate) },
                        label = { Text("${rate}×") }
                    )
                }
            }
        }

        SheetSection("Sleep timer") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ttsState.sleepMode == SleepMode.Off,
                    onClick = { onSleepChange(SleepMode.Off) },
                    label = { Text("Off") }
                )
                for (minutes in listOf(10, 20, 30)) {
                    FilterChip(
                        selected = ttsState.sleepMode == SleepMode.AfterMinutes(minutes),
                        onClick = { onSleepChange(SleepMode.AfterMinutes(minutes)) },
                        label = { Text("$minutes min") }
                    )
                }
                FilterChip(
                    selected = ttsState.sleepMode == SleepMode.EndOfChapter,
                    onClick = { onSleepChange(SleepMode.EndOfChapter) },
                    label = { Text("End of chapter") }
                )
            }
            val remaining = ttsState.sleepRemainingMs
            if (remaining != null && ttsState.sleepMode != SleepMode.Off) {
                Text(
                    "Stops in ${formatClock(remaining / 1000.0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatClock(totalSeconds: Double): String {
    val total = totalSeconds.toInt().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

// ---------------------------------------------------------------------------
// Chapters / Bookmarks / Highlights (PLAN §2.4 #7)
// ---------------------------------------------------------------------------

@Composable
fun TocSheet(
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    onJumpChapter: (Int) -> Unit,
    onJumpToPosition: (chapterIndex: Int, charOffset: Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDeleteHighlight: (Highlight) -> Unit,
    onEditHighlightNote: (Highlight) -> Unit,
    /** Phase 6: share a highlight as a quote-card PNG. */
    onQuoteCard: (Highlight) -> Unit = {},
    /** Phase 5: cached AI summary of the chapter on screen. */
    chapterSummary: app.xreader.data.db.AiSummary? = null,
    onSummarizeChapter: (() -> Unit)? = null
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chapters") })
            Tab(
                selected = tab == 1, onClick = { tab = 1 },
                text = { Text("Bookmarks (${bookmarks.size})") }
            )
            Tab(
                selected = tab == 2, onClick = { tab = 2 },
                text = { Text("Highlights (${highlights.size})") }
            )
        }
        when (tab) {
            0 -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                // Phase 5: this chapter's AI summary rides at the top
                // of the Contents list (§7 "reader's chapter sheet").
                item(key = "chapter-summary") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "This chapter, in short",
                            style = MaterialTheme.typography.titleSmall,
                            color = aiAccentColor()
                        )
                        val summary = chapterSummary
                        if (summary != null) {
                            app.xreader.data.ai.Summarizer
                                .decodeBullets(summary.bulletsJson)
                                .forEach { bullet ->
                                    Text(
                                        "• $bullet",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                        } else {
                            Text(
                                "No summary yet for this chapter.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (onSummarizeChapter != null) {
                                androidx.compose.material3.TextButton(
                                    onClick = onSummarizeChapter
                                ) { Text("Summarize with AI") }
                            }
                        }
                    }
                }
                itemsIndexed(chapters, key = { _, ch -> ch.id }) { index, chapter ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onJumpChapter(index) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(width = 40.dp, height = 20.dp)
                        )
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (index == currentChapterIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (chapter.read) {
                            Icon(
                                Icons.Filled.Check, "Read",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            1 -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                if (bookmarks.isEmpty()) {
                    item {
                        Text(
                            "No bookmarks yet. Tap the bookmark button while " +
                                "reading to mark a place.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                items(bookmarks, key = { it.id }) { bookmark ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onJumpToPosition(bookmark.chapterIndex, bookmark.charOffset)
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                bookmark.label.ifBlank {
                                    chapters.getOrNull(bookmark.chapterIndex)?.title
                                        ?: "Chapter ${bookmark.chapterIndex + 1}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Chapter ${bookmark.chapterIndex + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onDeleteBookmark(bookmark) }) {
                            Icon(Icons.Filled.Delete, "Remove bookmark")
                        }
                    }
                }
            }
            else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                if (highlights.isEmpty()) {
                    item {
                        Text(
                            "No highlights yet. In Scroll mode, press and hold " +
                                "a paragraph to highlight it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                items(highlights, key = { it.id }) { highlight ->
                    val chapterIndex = chapters.indexOfFirst { it.id == highlight.chapterId }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (chapterIndex >= 0) {
                                    onJumpToPosition(chapterIndex, highlight.startOffset)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                highlight.quotedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            val note = highlight.note
                            Text(
                                if (note != null) "Note: $note"
                                else "Tap the note button to add one",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onQuoteCard(highlight) }) {
                            Icon(Icons.Filled.Share, "Share as quote card")
                        }
                        IconButton(onClick = { onEditHighlightNote(highlight) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Edit note")
                        }
                        IconButton(onClick = { onDeleteHighlight(highlight) }) {
                            Icon(Icons.Filled.Delete, "Remove highlight")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Focus timer (PLAN §2.4 #28)
// ---------------------------------------------------------------------------

@Composable
fun FocusSheet(
    focus: FocusUiState,
    onStart: (minutes: Int, soundscape: Soundscape) -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onAcknowledgeDone: () -> Unit
) {
    var customMinutes by remember { mutableStateOf(25f) }
    var soundscape by remember { mutableStateOf(Soundscape.NONE) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Focus timer", style = MaterialTheme.typography.titleLarge)
        when (focus.state) {
            app.xreader.domain.reader.FocusState.IDLE -> {
                Text(
                    "Read without interruptions. Finishing a full session " +
                        "earns a focus bonus for this book.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (preset in listOf(25, 50)) {
                        FilterChip(
                            selected = customMinutes.roundToInt() == preset,
                            onClick = { customMinutes = preset.toFloat() },
                            label = { Text("$preset min") }
                        )
                    }
                }
                SheetSlider(
                    label = "Session length",
                    valueText = "${customMinutes.roundToInt()} min",
                    value = customMinutes,
                    range = 5f..120f,
                    onChange = { customMinutes = it }
                )
                SheetSection("Soundscape") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (sound in Soundscape.entries) {
                            FilterChip(
                                selected = soundscape == sound,
                                onClick = { soundscape = sound },
                                label = {
                                    Text(
                                        when (sound) {
                                            Soundscape.NONE -> "Quiet"
                                            Soundscape.RAIN -> "Rain"
                                            Soundscape.FIREPLACE -> "Fireplace"
                                            Soundscape.FOREST -> "Forest"
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
                XreaderPrimaryButton(
                    text = "Start focusing",
                    onClick = { onStart(customMinutes.roundToInt(), soundscape) }
                )
            }
            app.xreader.domain.reader.FocusState.RUNNING,
            app.xreader.domain.reader.FocusState.PAUSED -> {
                Text(
                    formatClock(focus.remainingMs / 1000.0),
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Text(
                    if (focus.state == app.xreader.domain.reader.FocusState.RUNNING) {
                        "Focusing — ${focus.soundscape.name.lowercase()} " +
                            "soundscape, ${focus.durationMinutes} min session"
                    } else {
                        "Paused — a paused session still counts, but the " +
                            "uninterrupted bonus needs a clean run"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    XreaderPrimaryButton(
                        text = if (focus.state ==
                            app.xreader.domain.reader.FocusState.RUNNING
                        ) "Pause" else "Resume",
                        onClick = onPauseResume
                    )
                    XreaderPrimaryButton(text = "End session", onClick = onStop)
                }
            }
            app.xreader.domain.reader.FocusState.DONE -> {
                Text(
                    "Focus complete",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    if (focus.completedUninterrupted) {
                        "A full uninterrupted session — the focus bonus " +
                            "will be added to this book's reading time."
                    } else {
                        "Session finished. Pauses along the way mean no " +
                            "uninterrupted bonus this time."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                XreaderPrimaryButton(text = "Done", onClick = onAcknowledgeDone)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
