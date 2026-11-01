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
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.sp
import app.xreader.data.db.Bookmark
import app.xreader.data.db.Chapter
import app.xreader.data.db.Highlight
import app.xreader.data.reader.OverlayPlayer
import app.xreader.data.reader.ReadAloudController
import app.xreader.domain.reader.EyeState
import app.xreader.domain.reader.PageTurnOverride
import app.xreader.domain.reader.TermPair
import app.xreader.ui.more.TermsEditor
import app.xreader.domain.reader.PageTurnTier
import app.xreader.domain.reader.ReaderAlignment
import app.xreader.domain.reader.ReaderFontChoice
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderMode
import app.xreader.domain.reader.SleepMode
import app.xreader.domain.reader.Soundscape
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.RowItem
import app.xreader.ui.common.SectionHeader
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    onSaveWord: (String) -> Unit = {},
    /** P0 (Reader Terms): this book's own term pairs and their
     * editor — the per-book half of Reader Terms (the global
     * list lives in Settings). Display-only: the book's own
     * text is never changed. */
    bookTerms: List<TermPair> = emptyList(),
    onBookTermsChange: (List<TermPair>) -> Unit = {},
    /** Nocturne §5.11: the reader chrome's Theme tile opens this
     * sheet straight on the "page" sub-page. */
    initialPage: String = "index",
    /** v1.3.2 (item 86): the resolved family for the font shelf's
     * live preview (the screen resolves it so user fonts work). */
    previewFontFamily: androidx.compose.ui.text.font.FontFamily =
        app.xreader.ui.theme.LiterataSerif
) {
    // v1.0.6.2 (F8c): the sheet is a short INDEX of plainly-named
    // groups now — not one long scroll. Each group is its own
    // small page inside the sheet; every control below is the
    // same control with the same stored value, regrouped, with
    // the explanation paragraphs cut to at most one short line.
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (page != "index") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { page = "index" }
                    .padding(vertical = 4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to settings",
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    when (page) {
                        "reading" -> "Reading"
                        "page" -> "Page & text"
                        "terms" -> "Terms for this book"
                        else -> "Extras"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        when (page) {
            "index" -> {
                SectionHeader(title = "Reading settings", eyebrow = "Reader",
    modifier = Modifier,
    actionText = null,
    onAction = null,

                )

                // R18 scope: at the TOP of the index, as before.
                SheetSection("Apply colours & text size to") {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NocturneChip(
                            label = "All books",
                            selected = settingsScope == ReaderSettingsScope.ALL_BOOKS,
                            onClick = { onScopeChange(ReaderSettingsScope.ALL_BOOKS) },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                        NocturneChip(
                            label = "This book only",
                            selected = settingsScope == ReaderSettingsScope.THIS_BOOK,
                            onClick = { onScopeChange(ReaderSettingsScope.THIS_BOOK) },
                            modifier = Modifier,
                            dotColor = null,
                        
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

                RowItem(
                    icon = Icons.Filled.MenuBook,
                    label = "Reading",
                    value = if (config.mode == ReaderMode.SCROLL) "Scroll" else "Pages",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { page = "reading" },
                    badge = null,
                
                )
                RowItem(
                    icon = Icons.Filled.Palette,
                    label = "Page & text",
                    value = "Page theme · ${config.paletteName}",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { page = "page" },
                    badge = null,
                
                )
                RowItem(
                    icon = Icons.Filled.Science,
                    label = "Extras",
                    value = "Words · Eye-tracking",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { page = "extras" },
                    badge = null,

                )
                RowItem(
                    icon = Icons.Filled.FindReplace,
                    label = "Terms for this book",
                    value = if (bookTerms.isEmpty()) {
                        "Name fixes for this book"
                    } else {
                        "${bookTerms.size} saved"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { page = "terms" },
                    badge = null,

                )
                TextButton(onClick = onOpenCustomise) {
                    Text("Open the Customise screen — colours, glass, layout and more")
                }
            }

            "reading" -> {
                SheetSection("Reading mode") {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NocturneChip(
                            label = "Pages",
                            selected = config.mode == ReaderMode.PAGED,
                            onClick = { onUpdate { it.copy(mode = ReaderMode.PAGED) } },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                        NocturneChip(
                            label = "Scroll",
                            selected = config.mode == ReaderMode.SCROLL,
                            onClick = { onUpdate { it.copy(mode = ReaderMode.SCROLL) } },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                    }
                }

                SheetSection("Moving between chapters") {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            app.xreader.domain.reader.ChapterTransition.CONTINUOUS to "Continuous",
                            app.xreader.domain.reader.ChapterTransition.CHAPTER_BY_CHAPTER to
                                "Chapter by chapter",
                            app.xreader.domain.reader.ChapterTransition.PULL_FOR_NEXT to
                                "Pull for next"
                        ).forEach { (value, label) ->
                            NocturneChip(
                                label = label,
                                selected = config.chapterTransition == value,
                                onClick = { onUpdate { it.copy(chapterTransition = value) } },
                                modifier = Modifier,
                                dotColor = null,
                            
                            )
                        }
                    }
                    SheetSwitch(
                        label = "Clock on the page",
                        checked = config.showPageOverlay,
                        onChange = { v -> onUpdate { it.copy(showPageOverlay = v) } }
                    )
                    Text(
                        "Keyboard control",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            app.xreader.domain.reader.KeyboardPreset.STANDARD to "Arrows + space",
                            app.xreader.domain.reader.KeyboardPreset.PAGE_KEYS to "Page keys",
                            app.xreader.domain.reader.KeyboardPreset.OFF to "Off"
                        ).forEach { (value, label) ->
                            NocturneChip(
                                label = label,
                                selected = config.keyboardPreset == value,
                                onClick = { onUpdate { it.copy(keyboardPreset = value) } },
                                modifier = Modifier,
                                dotColor = null,
                            
                            )
                        }
                    }
                    SheetSwitch(
                        label = "Reading line",
                        checked = config.readingLine,
                        onChange = { v -> onUpdate { it.copy(readingLine = v) } }
                    )
                }

                SheetSection("Auto-scroll") {
                    SheetSwitch(
                        label = "Auto-scroll / auto page-turn",
                        checked = config.autoScrollEnabled,
                        onChange = { v -> onUpdate { it.copy(autoScrollEnabled = v) } }
                    )
                    SheetSlider(
                        label = "Speed — ${autoScrollSpeedLabel(config.autoScrollSpeed)}",
                        valueText = "%.1f×".format(config.autoScrollSpeed),
                        value = config.autoScrollSpeed,
                        range = 0.25f..3f,
                        onChange = { v -> onUpdate { it.copy(autoScrollSpeed = v) } }
                    )
                }

                SheetSection("Page turns") {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NocturneChip(
                            label = "Automatic",
                            selected = config.turnOverride == PageTurnOverride.AUTO,
                            onClick = {
                                onUpdate { it.copy(turnOverride = PageTurnOverride.AUTO) }
                            },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                        NocturneChip(
                            label = "Curl",
                            selected = config.turnOverride == PageTurnOverride.FULL,
                            onClick = {
                                onUpdate { it.copy(turnOverride = PageTurnOverride.FULL) }
                            },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                        NocturneChip(
                            label = "Gooey",
                            selected = config.turnOverride == PageTurnOverride.MID,
                            onClick = {
                                onUpdate { it.copy(turnOverride = PageTurnOverride.MID) }
                            },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                        NocturneChip(
                            label = "Fade",
                            selected = config.turnOverride == PageTurnOverride.MINIMAL,
                            onClick = {
                                onUpdate { it.copy(turnOverride = PageTurnOverride.MINIMAL) }
                            },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                    }
                    Text(
                        "On this device: ${tierLabel(effectiveTier)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SheetSection("Taps & keys") {
                    Text(
                        "Tap zones",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NocturneChip(
                            label = "Standard",
                            selected = config.tapZoneLayout == TapZoneLayout.STANDARD,
                            onClick = { onUpdate { it.copy(tapZoneLayout = TapZoneLayout.STANDARD) } },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                        NocturneChip(
                            label = "Reversed",
                            selected = config.tapZoneLayout == TapZoneLayout.REVERSED,
                            onClick = { onUpdate { it.copy(tapZoneLayout = TapZoneLayout.REVERSED) } },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                        NocturneChip(
                            label = "Nine-zone",
                            selected = config.tapZoneLayout == TapZoneLayout.NINE_ZONE,
                            onClick = { onUpdate { it.copy(tapZoneLayout = TapZoneLayout.NINE_ZONE) } },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                        NocturneChip(
                            label = "Off",
                            selected = config.tapZoneLayout == TapZoneLayout.OFF,
                            onClick = { onUpdate { it.copy(tapZoneLayout = TapZoneLayout.OFF) } },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                    }
                    Text(
                        "Page order",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NocturneChip(
                            label = "Left to right",
                            selected = config.pagedDirection == PagedDirection.LTR,
                            onClick = { onUpdate { it.copy(pagedDirection = PagedDirection.LTR) } },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                        NocturneChip(
                            label = "Right to left",
                            selected = config.pagedDirection == PagedDirection.RTL,
                            onClick = { onUpdate { it.copy(pagedDirection = PagedDirection.RTL) } },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                    }
                    SheetSwitch(
                        label = "Volume keys turn pages",
                        checked = config.volumeKeyTurn,
                        onChange = { v -> onUpdate { it.copy(volumeKeyTurn = v) } }
                    )
                    SheetSwitch(
                        label = "Bionic reading (bold word starts)",
                        checked = config.bionicReading,
                        onChange = { v -> onUpdate { it.copy(bionicReading = v) } }
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
                }

                // v1.2.0 (WS2, manga zoom Option A): Mihon's
                // long-strip zoom settings, exact labels.
                SheetSection("Zoom") {
                    SheetSwitch(
                        label = "Double-tap to zoom",
                        checked = config.doubleTapToZoom,
                        onChange = { v -> onUpdate { it.copy(doubleTapToZoom = v) } }
                    )
                    SheetSwitch(
                        label = "Disable zoom out",
                        checked = config.disableZoomOut,
                        onChange = { v -> onUpdate { it.copy(disableZoomOut = v) } }
                    )
                }
            }

            "page" -> {
                SheetSection("Page theme") {
                    // v1.0.6.2 (F8c): exactly THREE cards in ONE
                    // visible row — AMOLED (default), Sepia, Paper.
                    // Ember dark is gone; the custom colours live
                    // in the "Custom colours" row below the cards.
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReaderPalettes.forEach { palette ->
                            val effective = palette.maxContrast(config.highContrastText)
                            val selected = config.paletteName == palette.name
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(118.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(effective.background)
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outline
                                            },
                                            shape = RoundedCornerShape(16.dp)
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
                }

                SheetSection("Custom colours") {
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
                            NocturneChip(
                                label = label,
                                selected = config.paletteName == "Custom" &&
                                    config.customPageArgb == argb,
                                onClick = {
                                    onUpdate { it.copy(paletteName = "Custom", customPageArgb = argb) }
                                },
                                modifier = Modifier,
                                dotColor = null,
                            
                            )
                        }
                    }
                    Text("Words", style = MaterialTheme.typography.labelMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        textChoices.forEach { (label, argb) ->
                            NocturneChip(
                                label = label,
                                selected = config.paletteName == "Custom" &&
                                    config.customTextArgb == argb,
                                onClick = {
                                    onUpdate { it.copy(paletteName = "Custom", customTextArgb = argb) }
                                },
                                modifier = Modifier,
                                dotColor = null,
                            
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
                        NocturneSwitch(
                            checked = config.brightness == null,
                            onCheckedChange = { system ->
                                onUpdate { it.copy(brightness = if (system) null else 0.7f) }
                            },
                            modifier = Modifier,
                            enabled = true,
                        
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

                SheetSection("Font") {
                    // v1.3.2 (item 86): a live preview in the
                    // currently chosen font — the shelf answers
                    // "what will it look like" before a tap.
                    val previewFamily = previewFontFamily
                    Text(
                        text = "The night was cold, and the river " +
                            "carried the lantern light a long way " +
                            "downstream.",
                        fontFamily = previewFamily,
                        fontSize = config.fontSizeSp.sp,
                        lineHeight = (config.fontSizeSp *
                            config.lineHeightMultiplier).sp,
                        color = primaryTextColor(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for ((label, choice) in listOf(
                            "Literata" to ReaderFontChoice.LITERATA,
                            "Lora" to ReaderFontChoice.LORA,
                            "Merriweather" to ReaderFontChoice.MERRIWEATHER,
                            "Noto Serif" to ReaderFontChoice.NOTO_SERIF,
                            "Jakarta" to ReaderFontChoice.JAKARTA,
                            "Inter" to ReaderFontChoice.INTER
                        )) {
                            NocturneChip(
                                label = label,
                                selected = config.fontChoice == choice,
                                onClick = {
                                    onUpdate { it.copy(fontChoice = choice) }
                                },
                                modifier = Modifier,
                                dotColor = null,
                            )
                        }
                    }
                    for (fontName in userFonts) {
                        NocturneChip(
                            label = fontName,
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
                            modifier = Modifier.padding(top = 6.dp),
                            dotColor = null,
                        
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
                // v1.3.2 (item 87): the spacing choice in plain
                // words — three named stops writing the same
                // stored dp value the slider used to write.
                SheetSection("Paragraph spacing") {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (spacing in
                            app.xreader.domain.reader.ParagraphSpacing.entries) {
                            NocturneChip(
                                label = spacing.label,
                                selected =
                                    app.xreader.domain.reader.ParagraphSpacing
                                        .of(config.paragraphSpacingDp) == spacing,
                                onClick = {
                                    onUpdate {
                                        it.copy(paragraphSpacingDp = spacing.dp)
                                    }
                                },
                                modifier = Modifier,
                                dotColor = null,
                            )
                        }
                    }
                }
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
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NocturneChip(
                            label = "Left",
                            selected = config.alignment == ReaderAlignment.LEFT,
                            onClick = { onUpdate { it.copy(alignment = ReaderAlignment.LEFT) } },
                            modifier = Modifier,
                            dotColor = null,
                        
                        )
                        NocturneChip(
                            label = "Justified",
                            selected = config.alignment == ReaderAlignment.JUSTIFY,
                            onClick = {
                                onUpdate { it.copy(alignment = ReaderAlignment.JUSTIFY) }
                            },
                            modifier = Modifier,
                            dotColor = null,
                        
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
            }

            "terms" -> {
                Text(
                    "Names or words in THIS book that you want " +
                        "shown differently. They apply to this " +
                        "book only — for every book, use " +
                        "Settings → Reader terms. The book's own " +
                        "text is never changed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TermsEditor(
                    pairs = bookTerms,
                    onChange = onBookTermsChange
                )
            }

            else -> {
                SheetSection("Words I'm learning") {
                    var wordText by remember { mutableStateOf("") }
                    var wordSavedNote by remember { mutableStateOf(false) }
                    Text(
                        "Save a word with its sentence — find it in Me → Words I'm learning.",
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
                        NocturneSwitch(
                            checked = config.eyeTracking,
                            onCheckedChange = { v -> onUpdate { it.copy(eyeTracking = v) } },
                            modifier = Modifier,
                            enabled = true,
                        
                        )
                    }
                    Text(
                        "Uses the front camera on this device only — nothing is recorded or sent anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
        SectionHeader(title = title,
    modifier = Modifier,
    eyebrow = null,
    actionText = null,
    onAction = null,

        )
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
        NocturneSwitch(checked = checked, onCheckedChange = onChange,
    modifier = Modifier,
    enabled = true,

        )
    }
}

// ---------------------------------------------------------------------------
// Listen (PLAN §6 hybrid TTS + Media Overlay transport)
// ---------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
        SectionHeader(title = "Listen", eyebrow = "Read aloud",
    modifier = Modifier,
    actionText = null,
    onAction = null,

        )

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
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (rate in listOf(0.75f, 1f, 1.25f, 1.5f, 2f)) {
                    NocturneChip(
                        label = "${rate}×",
                        selected = speechRate == rate,
                        onClick = { onRateChange(rate) },
                        modifier = Modifier,
                        dotColor = null,
                    
                    )
                }
            }
        }

        SheetSection("Sleep timer") {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NocturneChip(
                    label = "Off",
                    selected = ttsState.sleepMode == SleepMode.Off,
                    onClick = { onSleepChange(SleepMode.Off) },
                    modifier = Modifier,
                    dotColor = null,
                
                )
                for (minutes in listOf(10, 20, 30)) {
                    NocturneChip(
                        label = "$minutes min",
                        selected = ttsState.sleepMode == SleepMode.AfterMinutes(minutes),
                        onClick = { onSleepChange(SleepMode.AfterMinutes(minutes)) },
                        modifier = Modifier,
                        dotColor = null,
                    
                    )
                }
                NocturneChip(
                    label = "End of chapter",
                    selected = ttsState.sleepMode == SleepMode.EndOfChapter,
                    onClick = { onSleepChange(SleepMode.EndOfChapter) },
                    modifier = Modifier,
                    dotColor = null,
                
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
        SectionHeader(title = "Contents", eyebrow = "This book",
    modifier = Modifier,
    actionText = null,
    onAction = null,

        )
        Spacer(Modifier.height(8.dp))
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
                        // v1.0.6.2 (R2): a quiet "saved" word for
                        // chapters already on the phone — it reads
                        // offline and opens instantly.
                        if (chapter.contentPath != null) {
                            Text(
                                "saved",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
        SectionHeader(title = "Focus timer", eyebrow = "Reader",
    modifier = Modifier,
    actionText = null,
    onAction = null,

        )
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
                        NocturneChip(
                            label = "$preset min",
                            selected = customMinutes.roundToInt() == preset,
                            onClick = { customMinutes = preset.toFloat() },
                            modifier = Modifier,
                            dotColor = null,
                        
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
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (sound in Soundscape.entries) {
                            NocturneChip(
                                label = when (sound) {
                                            Soundscape.NONE -> "Quiet"
                                            Soundscape.RAIN -> "Rain"
                                            Soundscape.FIREPLACE -> "Fireplace"
                                            Soundscape.FOREST -> "Forest"
                                        },
                                selected = soundscape == sound,
                                onClick = { soundscape = sound },
                                modifier = Modifier,
                                dotColor = null,
                            
                            )
                        }
                    }
                }
                PrimaryPill(
                    text = "Start focusing",
                    onClick = { onStart(customMinutes.roundToInt(), soundscape) },
                    modifier = Modifier,
                    enabled = true,
                
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
                    PrimaryPill(
                        text = if (focus.state ==
                            app.xreader.domain.reader.FocusState.RUNNING
                        ) "Pause" else "Resume",
                        onClick = onPauseResume,
                        modifier = Modifier,
                        enabled = true,
                    
                    )
                    PrimaryPill(text = "End session", onClick = onStop,
    modifier = Modifier,
    enabled = true,

                    )
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
                PrimaryPill(text = "Done", onClick = onAcknowledgeDone,
    modifier = Modifier,
    enabled = true,

                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
