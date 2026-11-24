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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xreader.ui.localreader.toggledNight
import app.xreader.ui.localreader.withFilter
import app.xreader.data.db.Bookmark
import app.xreader.data.db.Chapter
import app.xreader.data.db.Highlight
import app.xreader.data.reader.OverlayPlayer
import app.xreader.data.reader.ReadAloudController
import app.xreader.domain.reader.EyeState
import app.xreader.domain.reader.PageTurnOverride
import app.xreader.domain.reader.TermPair
import app.xreader.domain.reader.PageTurnTier
import app.xreader.domain.reader.ReaderAlignment
import app.xreader.domain.reader.ReaderFontChoice
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderMode
import app.xreader.domain.reader.SleepMode
import app.xreader.domain.reader.Soundscape
import app.xreader.ui.common.CHAPTER_RANGE_SIZE
import app.xreader.ui.common.CHAPTER_RANGE_THRESHOLD
import app.xreader.ui.common.ChapterRangeBar
import app.xreader.ui.common.ElasticSlider
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.RowItem
import app.xreader.ui.common.SectionHeader
import app.xreader.domain.reader.PagedDirection
import app.xreader.domain.reader.TapZoneLayout
import kotlinx.coroutines.launch
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
    /** P0 (Reader Terms): this book's own term pairs — the
     * index row shows their count. Patch 11: the pairs
     * themselves live on their own full PAGE now (the owner's
     * anti-clutter word); the sheet keeps only the door, and
     * [onOpenBookTerms] opens that page. Display-only: the
     * book's own text is never changed. */
    bookTerms: List<TermPair> = emptyList(),
    onOpenBookTerms: () -> Unit = {},
    /** Nocturne §5.11: the reader chrome's Theme tile opens this
     * sheet straight on the "page" sub-page. */
    initialPage: String = "index",
    /** v1.3.2 (item 86): the resolved family for the font shelf's
     * live preview (the screen resolves it so user fonts work). */
    previewFontFamily: androidx.compose.ui.text.font.FontFamily =
        app.xreader.ui.theme.LiterataSerif,
    /** v1.5.0 (Part C): the manga section — present only for
     * image chapters. Every control writes THIS series'
     * override (option-scope tier 2); the full global surface
     * lives in Settings → Customise. */
    mangaSettings: MangaSettings? = null,
    onMangaModeChange: (app.xreader.domain.reader.ImageReaderMode) -> Unit = {},
    onMangaTapLayoutChange: (app.xreader.domain.reader.MangaTapLayout?) -> Unit = {},
    onMangaOrientationChange: (app.xreader.domain.reader.MangaOrientation?) -> Unit = {},
    onMangaCropChange: (Boolean?) -> Unit = {},
    mangaHasOverrides: Boolean = false,
    onResetMangaOverrides: () -> Unit = {},
    /** v1.7 OE-1 (R21): a PDF's page settings — present
     * only for a PDF read in this reader. The retired
     * printed surface's display sheet, re-homed: Pages-mode
     * controls on the one reader's own settings sheet. The
     * mode switch itself rides the reader's bar (OQ4),
     * never this sheet. */
    pdfPagesSettings: app.xreader.ui.localreader.PdfDocSettings? = null,
    onPdfSettingsChange: (
        (app.xreader.ui.localreader.PdfDocSettings) ->
            app.xreader.ui.localreader.PdfDocSettings
    ) -> Unit = {},
    pdfAutoResolvedFilter: app.xreader.data.engine.PdfPageFilter =
        app.xreader.data.engine.PdfPageFilter.NONE,
    pdfAutoScrollOn: Boolean = false,
    onPdfAutoScrollToggle: (Boolean) -> Unit = {},
    /** D13: this book's footnotes-in-text value — present
     * only for a prepared EPUB in this reader (the screen
     * passes null otherwise, and the section never renders).
     * The switch writes THIS book's override (option-scope:
     * a change inside the reader is per-book); the global
     * default lives in Settings → Customise. */
    footnotesInText: Boolean? = null,
    onFootnotesInTextChange: (Boolean) -> Unit = {}
) {
    // v1.0.6.2 (F8c): the sheet is a short INDEX of plainly-named
    // groups now — not one long scroll. Each group is its own
    // small page inside the sheet; every control below is the
    // same control with the same stored value, regrouped, with
    // the explanation paragraphs cut to at most one short line.
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    // Code 122 (Settings V2, S16): under Material 3 this sheet
    // speaks the redesign's sheet language — the index doors
    // ride in one tonal card of segmented rows, the Scope
    // choice is a spec segmented row in the sheet's last card,
    // and a sub-page's groups sit on one tonal card a rung
    // above the sheet. Every setting and every wire below is
    // unchanged; Nocturne renders exactly as shipped.
    val isM3Settings = app.xreader.ui.theme.LocalDesignState
        .current.design !=
        app.xreader.data.prefs.AppDesign.NOCTURNE
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (isM3Settings) 16.dp else 24.dp,
                vertical = 8.dp
            )
            .then(
                if (isM3Settings && page != "index") {
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                } else {
                    Modifier
                }
            ),
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
                        "page" -> "Text & colours"
                        "manga" -> "Manga — this series"
                        else -> "Extras"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        when (page) {
            "index" -> {
                if (isM3Settings) {
                    Text(
                        "Reading settings",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    SectionHeader(title = "Reading settings", eyebrow = "Reader",
    modifier = Modifier,
    actionText = null,
    onAction = null,

                    )
                }

                // v1.7 OE-1 (R21): a PDF's page settings
                // stand at the TOP of the index — one
                // sheet-open, no door-hunting (the F1
                // principle, kept).
                val pdfSettings = pdfPagesSettings
                if (pdfSettings != null) {
                    SheetSection("PDF pages") {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NocturneChip(
                                label = "Page by page",
                                selected = pdfSettings.scrollModel ==
                                    app.xreader.ui.localreader
                                        .PdfScrollModel.PAGED,
                                onClick = {
                                    onPdfSettingsChange {
                                        it.copy(
                                            scrollModel =
                                                app.xreader.ui.localreader
                                                    .PdfScrollModel.PAGED
                                        )
                                    }
                                },
                                modifier = Modifier,
                                dotColor = null
                            )
                            NocturneChip(
                                label = "Scroll down",
                                selected = pdfSettings.scrollModel ==
                                    app.xreader.ui.localreader
                                        .PdfScrollModel.CONTINUOUS,
                                onClick = {
                                    onPdfSettingsChange {
                                        it.copy(
                                            scrollModel =
                                                app.xreader.ui.localreader
                                                    .PdfScrollModel.CONTINUOUS
                                        )
                                    }
                                },
                                modifier = Modifier,
                                dotColor = null
                            )
                        }
                        if (pdfSettings.scrollModel ==
                            app.xreader.ui.localreader
                                .PdfScrollModel.PAGED
                        ) {
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                NocturneChip(
                                    label = "Fit page",
                                    selected = pdfSettings.fitMode ==
                                        app.xreader.ui.localreader
                                            .PdfFitMode.FIT_PAGE,
                                    onClick = {
                                        onPdfSettingsChange {
                                            it.copy(
                                                fitMode =
                                                    app.xreader.ui.localreader
                                                        .PdfFitMode.FIT_PAGE
                                            )
                                        }
                                    },
                                    modifier = Modifier,
                                    dotColor = null
                                )
                                NocturneChip(
                                    label = "Fit width",
                                    selected = pdfSettings.fitMode ==
                                        app.xreader.ui.localreader
                                            .PdfFitMode.FIT_WIDTH,
                                    onClick = {
                                        onPdfSettingsChange {
                                            it.copy(
                                                fitMode =
                                                    app.xreader.ui.localreader
                                                        .PdfFitMode.FIT_WIDTH
                                            )
                                        }
                                    },
                                    modifier = Modifier,
                                    dotColor = null
                                )
                            }
                            SheetSwitch(
                                label = "Two-page spread in landscape",
                                checked = pdfSettings.spread,
                                onChange = { value ->
                                    onPdfSettingsChange {
                                        it.copy(spread = value)
                                    }
                                }
                            )
                            SheetSwitch(
                                label = "Right-to-left paging",
                                checked = pdfSettings.rtlPaging,
                                onChange = { value ->
                                    onPdfSettingsChange {
                                        it.copy(rtlPaging = value)
                                    }
                                }
                            )
                        }
                    }
                    SheetSection("Page edges & colour") {
                        SheetSlider(
                            label = "Trim page edges",
                            valueText = "${(pdfSettings.edgeTrim * 100).toInt()}%",
                            value = pdfSettings.edgeTrim,
                            range = 0f..0.20f,
                            onChange = { value ->
                                onPdfSettingsChange {
                                    it.copy(edgeTrim = value)
                                }
                            }
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val filterLabels = listOf(
                                app.xreader.data.engine.PdfPageFilter.NONE
                                    to "As printed",
                                app.xreader.data.engine.PdfPageFilter.INVERT
                                    to "Invert",
                                app.xreader.data.engine.PdfPageFilter
                                    .BLACK_AND_WHITE to "Black & white",
                                app.xreader.data.engine.PdfPageFilter
                                    .INVERT_BLACK_AND_WHITE to "Invert B&W",
                                app.xreader.data.engine.PdfPageFilter
                                    .SEPIA_PAGE to "Sepia page",
                                app.xreader.data.engine.PdfPageFilter
                                    .SOFT_GREY to "Soft grey"
                            )
                            filterLabels.forEach { (filter, label) ->
                                NocturneChip(
                                    label = label,
                                    selected = pdfSettings.filter == filter &&
                                        !pdfSettings.autoContrast,
                                    onClick = {
                                        onPdfSettingsChange {
                                            it.withFilter(filter)
                                        }
                                    },
                                    modifier = Modifier,
                                    dotColor = null
                                )
                            }
                            NocturneChip(
                                label = "Auto (smart contrast)",
                                selected = pdfSettings.autoContrast,
                                onClick = {
                                    onPdfSettingsChange {
                                        it.copy(autoContrast = true)
                                    }
                                },
                                modifier = Modifier,
                                dotColor = null
                            )
                        }
                        SheetSwitch(
                            label = "Night pages",
                            checked = !pdfSettings.autoContrast &&
                                (pdfSettings.filter ==
                                    app.xreader.data.engine.PdfPageFilter
                                        .INVERT ||
                                    pdfSettings.filter ==
                                    app.xreader.data.engine.PdfPageFilter
                                        .INVERT_BLACK_AND_WHITE),
                            onChange = {
                                onPdfSettingsChange {
                                    it.toggledNight(pdfAutoResolvedFilter)
                                }
                            }
                        )
                    }
                    SheetSection("Comfort") {
                        SheetSwitch(
                            label = "Volume keys turn pages",
                            checked = pdfSettings.volumeKeyTurn,
                            onChange = { value ->
                                onPdfSettingsChange {
                                    it.copy(volumeKeyTurn = value)
                                }
                            }
                        )
                        SheetSwitch(
                            label = "Keep screen on",
                            checked = pdfSettings.keepScreenOn,
                            onChange = { value ->
                                onPdfSettingsChange {
                                    it.copy(keepScreenOn = value)
                                }
                            }
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val orientations = listOf(
                                app.xreader.ui.localreader
                                    .ReaderOrientation.FREE to "Free",
                                app.xreader.ui.localreader
                                    .ReaderOrientation.PORTRAIT to "Portrait",
                                app.xreader.ui.localreader
                                    .ReaderOrientation.LANDSCAPE to "Landscape"
                            )
                            orientations.forEach { (orientation, label) ->
                                NocturneChip(
                                    label = label,
                                    selected =
                                        pdfSettings.orientation == orientation,
                                    onClick = {
                                        onPdfSettingsChange {
                                            it.copy(orientation = orientation)
                                        }
                                    },
                                    modifier = Modifier,
                                    dotColor = null
                                )
                            }
                        }
                        SheetSwitch(
                            label = "Auto-scroll",
                            checked = pdfAutoScrollOn,
                            onChange = onPdfAutoScrollToggle
                        )
                        if (pdfAutoScrollOn) {
                            SheetSlider(
                                label = "Auto-scroll speed",
                                valueText = String.format(
                                    "%.1f×", pdfSettings.autoScrollSpeed
                                ),
                                value = pdfSettings.autoScrollSpeed.toFloat(),
                                range = 0.25f..4f,
                                onChange = { value ->
                                    onPdfSettingsChange {
                                        it.copy(
                                            autoScrollSpeed =
                                                value.toDouble()
                                        )
                                    }
                                }
                            )
                        }
                        if (pdfSettings.zoom > 1.01f) {
                            TextButton(
                                onClick = {
                                    onPdfSettingsChange {
                                        it.copy(zoom = 1f)
                                    }
                                }
                            ) {
                                Text("Reset zoom")
                            }
                        }
                    }
                }

                // R18 scope: at the TOP of the index, as before.
                // Code 122 (M3): under Material 3 the scope is
                // the sheet's LAST card in segmented-row form
                // (Settings V2 §2) — rendered at the end of this
                // index branch; this top section is Nocturne's.
                if (!isM3Settings) {
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
                }

                // v1.5.40: "Text & colours" is the FIRST row —
                // the community-feedback lesson (a reader could
                // not find text size, paragraph spacing or font
                // colour) was that these controls sat behind the
                // wrong door. Text size, paragraph gap and the
                // colours lead this page now; "Reading" below it
                // keeps only reading behaviour.
                // Code 122 (M3): the index doors ride in ONE
                // tonal card (surfaceContainerLow) as a joined
                // segmented list; Nocturne keeps the plain
                // column, spacing unchanged.
                Column(
                    Modifier.then(
                        if (isM3Settings) {
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    MaterialTheme.colorScheme
                                        .surfaceContainerLow
                                )
                                .padding(8.dp)
                        } else {
                            Modifier
                        }
                    ),
                    verticalArrangement = Arrangement.spacedBy(
                        if (isM3Settings) 2.dp else 12.dp
                    )
                ) {
                RowItem(
                    icon = Icons.Filled.Palette,
                    label = "Text & colours",
                    value = "Size · spacing · colours · ${config.paletteName}",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { page = "page" },
                    badge = null,

                )
                RowItem(
                    icon = Icons.Filled.MenuBook,
                    label = "Reading",
                    value = if (config.mode == ReaderMode.SCROLL) "Scroll" else "Pages",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { page = "reading" },
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
                    onClick = onOpenBookTerms,
                    badge = null,

                )
                if (mangaSettings != null) {
                    RowItem(
                        icon = Icons.Filled.Image,
                        label = "Manga reading",
                        value = mangaSettings.mode.label,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { page = "manga" },
                        badge = null,

                    )
                }
                }
                TextButton(onClick = onOpenCustomise) {
                    Text("Open the Customise screen — colours, glass, layout and more")
                }
                if (isM3Settings) {
                    // Code 122 (Settings V2 §2): the Scope choice
                    // is the M3 sheet's LAST card — a spec
                    // segmented row (filled picked state +
                    // check), the stated override line intact.
                    androidx.compose.material3.Card(
                        colors = androidx.compose.material3
                            .CardDefaults.cardColors(
                                containerColor = MaterialTheme
                                    .colorScheme.surfaceContainer
                            ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Apply colours & text size to",
                                style = MaterialTheme.typography
                                    .bodyLarge
                            )
                            androidx.compose.material3
                                .SingleChoiceSegmentedButtonRow(
                                    Modifier.fillMaxWidth()
                                ) {
                                    val scopeOptions = listOf(
                                        ReaderSettingsScope.ALL_BOOKS
                                            to "All books",
                                        ReaderSettingsScope.THIS_BOOK
                                            to "This book only"
                                    )
                                    scopeOptions.forEachIndexed {
                                        index, (scope, label) ->
                                        SegmentedButton(
                                                selected =
                                                    settingsScope == scope,
                                                onClick = {
                                                    onScopeChange(scope)
                                                },
                                                shape = androidx
                                                    .compose.material3
                                                    .SegmentedButtonDefaults
                                                    .itemShape(
                                                        index = index,
                                                        count =
                                                            scopeOptions.size
                                                    ),
                                                colors = androidx
                                                    .compose.material3
                                                    .SegmentedButtonDefaults
                                                    .colors(
                                                        activeContainerColor =
                                                            MaterialTheme
                                                                .colorScheme
                                                                .secondaryContainer,
                                                        activeContentColor =
                                                            MaterialTheme
                                                                .colorScheme
                                                                .onSecondaryContainer
                                                    ),
                                                icon = {
                                                    androidx.compose
                                                        .material3
                                                        .SegmentedButtonDefaults
                                                        .Icon(
                                                            active =
                                                                settingsScope ==
                                                                    scope
                                                        )
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                            ) {
                                                Text(label)
                                            }
                                    }
                                }
                            if (hasBookOverride) {
                                Text(
                                    "This book has its own " +
                                        "colours/text size.",
                                    style = MaterialTheme.typography
                                        .bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                                TextButton(
                                    onClick = onClearBookOverride
                                ) {
                                    Text(
                                        "Use the global look for " +
                                            "this book"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            "manga" -> {
                val manga = mangaSettings
                if (manga != null) {
                    Text(
                        "These apply to this series only. The " +
                            "defaults for every series live in " +
                            "Settings → Customise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SheetSection("Reading mode") {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (option in app.xreader.domain.reader
                                .ImageReaderMode.entries) {
                                NocturneChip(
                                    label = option.label,
                                    selected = manga.mode == option,
                                    onClick = { onMangaModeChange(option) },
                                    modifier = Modifier,
                                    dotColor = null,

                                )
                            }
                        }
                    }
                    SheetSection("Tap zones") {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (option in app.xreader.domain.reader
                                .MangaTapLayout.entries) {
                                NocturneChip(
                                    label = option.label,
                                    selected = manga.tapLayout == option,
                                    onClick = {
                                        onMangaTapLayoutChange(option)
                                    },
                                    modifier = Modifier,
                                    dotColor = null,

                                )
                            }
                        }
                    }
                    SheetSection("Screen direction") {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (option in app.xreader.domain.reader
                                .MangaOrientation.entries) {
                                NocturneChip(
                                    label = option.label,
                                    selected = manga.orientation == option,
                                    onClick = {
                                        onMangaOrientationChange(option)
                                    },
                                    modifier = Modifier,
                                    dotColor = null,

                                )
                            }
                        }
                    }
                    SheetSection("Page edges") {
                        SheetSwitch(
                            label = "Crop white/black borders",
                            checked = manga.cropBorders,
                            onChange = { onMangaCropChange(it) }
                        )
                    }
                    if (mangaHasOverrides) {
                        TextButton(onClick = onResetMangaOverrides) {
                            Text("Reset this series' manga settings")
                        }
                    }
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
                                "Chapter by chapter"
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
                    // v1.7 Moon+ standard fix (Part 3): the
                    // chapter-end wall's scope, riding directly
                    // under the transition it belongs to (the
                    // wall only exists in Chapter by chapter).
                    Text(
                        "Chapter-end wall",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            app.xreader.domain.reader.ChapterEndWall.FOR_EPUB_BOOKS to
                                "For EPUB books",
                            app.xreader.domain.reader.ChapterEndWall.EVERY_BOOK to
                                "Every book",
                            app.xreader.domain.reader.ChapterEndWall.OFF to "Off"
                        ).forEach { (value, label) ->
                            NocturneChip(
                                label = label,
                                selected = config.chapterEndWall == value,
                                onClick = { onUpdate { it.copy(chapterEndWall = value) } },
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
                // v1.5.40: this page leads with what a reader
                // reaches for — text size, paragraph spacing
                // and the colours (presets + the colour wheel);
                // themes and fonts sit below them.
                SheetSlider(
                    label = "Text size",
                    valueText = "${config.fontSizeSp.roundToInt()} sp",
                    value = config.fontSizeSp,
                    range = 12f..34f,
                    onChange = { v -> onUpdate { it.copy(fontSizeSp = v) } }
                )

                // v1.3.2 (item 87): the spacing choice in plain
                // words — three named stops writing the same
                // stored dp value; v1.5.41 adds the exact-gap
                // slider back beneath them (see the section).
                SheetSection("Paragraph spacing") {
                    // v1.5.41 (audit item 3): the three named
                    // stops stay as quick picks, and the slider
                    // beneath sets the exact gap — the stored
                    // value was always a plain dp float, so a
                    // custom gap needs no storage change. Chips
                    // now light ONLY on an exact match (the old
                    // nearest-stop match lit a chip for values
                    // that were not that stop); a custom value
                    // lights none and the slider's readout says
                    // "Custom" — the state is never dressed up
                    // as a preset. Both ways stay in sync: a
                    // chip tap moves the slider, the slider
                    // landing exactly on a stop lights its chip.
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
                                        .exact(config.paragraphSpacingDp) == spacing,
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
                    val spacingExact =
                        app.xreader.domain.reader.ParagraphSpacing
                            .exact(config.paragraphSpacingDp)
                    SheetSlider(
                        label = "Exact gap",
                        valueText = if (spacingExact != null) {
                            "${config.paragraphSpacingDp.roundToInt()} dp" +
                                " · ${spacingExact.label}"
                        } else {
                            "${config.paragraphSpacingDp.roundToInt()} dp" +
                                " · Custom"
                        },
                        value = config.paragraphSpacingDp,
                        range = app.xreader.domain.reader
                            .ReaderValidation.PARAGRAPH_SPACING_RANGE,
                        onChange = { v ->
                            onUpdate { it.copy(paragraphSpacingDp = v) }
                        }
                    )
                    // v1.7 (code 106): Moon+'s first-line
                    // indent, beside the spacing it pairs
                    // with (at the Moon+ defaults paragraphs
                    // separate by this indent alone). The
                    // depth is Moon+'s factory 2 em, carried
                    // in the config; the sheet's door is the
                    // plain on/off.
                    SheetSwitch(
                        label = "Indent the first line of paragraphs",
                        checked = config.paragraphIndent,
                        onChange = { v ->
                            onUpdate { it.copy(paragraphIndent = v) }
                        }
                    )
                }
                // D13 (Moon+ copy): the footnotes switch,
                // Moon+'s own words for it. Per-book here (the
                // option-scope rule); the global default is in
                // Customise. A prepared EPUB only.
                if (footnotesInText != null) {
                    SheetSection("Footnotes") {
                        SheetSwitch(
                            label = "Show footnotes directly in chapter text",
                            checked = footnotesInText,
                            onChange = onFootnotesInTextChange
                        )
                        Text(
                            "For this book only. Takes effect the " +
                                "next time this book opens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                SheetSection("Custom colours") {
                    // v1.5.40: which colour the wheel is picking
                    // ("page" / "words"), or null when it is shut.
                    var wheelFor by remember { mutableStateOf<String?>(null) }
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
                    // v1.5.41: the wheel's door is a full-width
                    // button under each colour — on the v1.5.40
                    // phone proof the small text links here were
                    // "very hidden" and the wheel went unfound.
                    app.xreader.ui.common.PickExactColourButton(
                        onClick = { wheelFor = "page" }
                    )
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
                    // v1.5.41: the second door, for the word
                    // colour — same button, same wheel, writing
                    // the same custom colour the chips write.
                    app.xreader.ui.common.PickExactColourButton(
                        onClick = { wheelFor = "words" }
                    )
                    when (wheelFor) {
                        "page" -> app.xreader.ui.common.ColourWheelPickerDialog(
                            title = "Page colour",
                            initialArgb = config.customPageArgb,
                            onDismiss = { wheelFor = null },
                            onPick = { argb ->
                                onUpdate {
                                    it.copy(
                                        paletteName = "Custom",
                                        customPageArgb = argb
                                    )
                                }
                                wheelFor = null
                            }
                        )
                        "words" -> app.xreader.ui.common.ColourWheelPickerDialog(
                            title = "Word colour",
                            initialArgb = config.customTextArgb,
                            onDismiss = { wheelFor = null },
                            onPick = { argb ->
                                onUpdate {
                                    it.copy(
                                        paletteName = "Custom",
                                        customTextArgb = argb
                                    )
                                }
                                wheelFor = null
                            }
                        )
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

                SheetSlider(
                    label = "Line height",
                    valueText = "%.2f×".format(config.lineHeightMultiplier),
                    value = config.lineHeightMultiplier,
                    range = 1.1f..2.2f,
                    onChange = { v -> onUpdate { it.copy(lineHeightMultiplier = v) } }
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
        // Patch 13 (Elastic Slider): every sheet slider gains
        // the rubber-band feel through this one swap; onChange
        // stays LIVE on every movement, exactly as before.
        ElasticSlider(value = value, onValueChange = onChange, valueRange = range)
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
    onSleepChange: (SleepMode) -> Unit,
    // Patch 12: the Voice section (this book's engine override +
    // Edge voice pick) and the honest status line's inputs.
    voiceEngine: app.xreader.data.prefs.VoiceEngine =
        app.xreader.data.prefs.VoiceEngine.DEVICE,
    voiceEngineIsOverride: Boolean = false,
    voiceLanguage: String = "en-US",
    voiceChoices: List<app.xreader.data.reader.EdgeVoiceTable.Voice> = emptyList(),
    voiceId: String? = null,
    voiceHasOverride: Boolean = false,
    onVoiceEngineChange: (app.xreader.data.prefs.VoiceEngine) -> Unit = {},
    onVoicePick: (String?) -> Unit = {},
    onVoiceReset: () -> Unit = {},
    // Code 122 (S17, audit #16): the listening-cache line + its
    // own Clear. The host routes the SAME call Customise →
    // Storage's Clear makes (TtsAudioCache.clear via the
    // container); null (the default) = the host supplied no
    // cache door and the section is not drawn — no dead button.
    listeningCacheBytes: Long? = null,
    onClearListeningCache: (() -> Unit)? = null
) {
    // Code 121 (S17): under M3 the transport's play/pause is a
    // FAB; Nocturne keeps its shipped icon-button transport.
    val listenDesignState = app.xreader.ui.theme.LocalDesignState.current
    val listenIsM3 = listenDesignState.design !=
        app.xreader.data.prefs.AppDesign.NOCTURNE
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

        // Patch 12: the transport section's header IS the honest
        // status line — which voice surface is actually speaking.
        SheetSection(
            when (ttsState.voiceStatus.surface) {
                app.xreader.data.reader.VoiceSurface.ONLINE ->
                    "Online voice — ${ttsState.voiceStatus.voiceName ?: "Edge"}"
                app.xreader.data.reader.VoiceSurface.OFFLINE ->
                    "Offline voice — ${ttsState.voiceStatus.voiceName ?: "pack"}"
                app.xreader.data.reader.VoiceSurface.DEVICE ->
                    if (ttsState.voiceStatus.fellBack) {
                        "Device voice — online unreachable"
                    } else {
                        "Device voice"
                    }
            }
        ) {
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
                    if (listenIsM3) {
                        FloatingActionButton(
                            onClick = onTtsPlayPause
                        ) {
                            Icon(
                                if (ttsState.playing) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                                if (ttsState.playing) "Pause" else "Read aloud"
                            )
                        }
                    } else {
                        IconButton(onClick = onTtsPlayPause) {
                            Icon(
                                if (ttsState.playing) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                                if (ttsState.playing) "Pause" else "Read aloud",
                                modifier = Modifier.size(36.dp)
                            )
                        }
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

        // Patch 12: this book's voice — the engine override and,
        // for the online voice, the curated voice for the
        // chapter's language. Writes are per-book only.
        SheetSection("Voice") {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NocturneChip(
                    label = "Device voice",
                    selected = voiceEngine ==
                        app.xreader.data.prefs.VoiceEngine.DEVICE,
                    onClick = {
                        onVoiceEngineChange(
                            app.xreader.data.prefs.VoiceEngine.DEVICE
                        )
                    },
                    modifier = Modifier,
                    dotColor = null,

                )
                NocturneChip(
                    label = "Online voice",
                    selected = voiceEngine ==
                        app.xreader.data.prefs.VoiceEngine.EDGE,
                    onClick = {
                        onVoiceEngineChange(
                            app.xreader.data.prefs.VoiceEngine.EDGE
                        )
                    },
                    modifier = Modifier,
                    dotColor = null,

                )
            }
            if (!voiceEngineIsOverride) {
                Text(
                    "Following the choice for all books (Providers & keys). " +
                        "Picking here changes this book only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (voiceEngine == app.xreader.data.prefs.VoiceEngine.EDGE) {
                Text(
                    "Chapter text is sent to Microsoft for online voices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val defaultVoice = voiceChoices.firstOrNull()
                voiceChoices.forEach { voice ->
                    VoicePickRow(
                        label = voice.displayName,
                        note = if (voice == defaultVoice) {
                            "Default for this language"
                        } else {
                            null
                        },
                        selected = (voiceId ?: defaultVoice?.id) == voice.id,
                        onClick = { onVoicePick(voice.id) }
                    )
                }
                if (voiceChoices.isEmpty()) {
                    Text(
                        "No picked voices for this chapter's language — " +
                            "the language's own online voice reads it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (voiceHasOverride) {
                TextButton(onClick = onVoiceReset) {
                    Text("Use the settings for all books")
                }
            }
        }

        SheetSection("Speed") {
            // v1.5.41 (audit item 3): the five rates stay as
            // quick picks and the slider beneath sets any rate
            // in the validated range — speech rate is a
            // continuous quantity and was capped at the chips.
            // The stored value was always a plain float, so no
            // storage change; chip selection was already exact,
            // so a custom rate lights no chip.
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
            SheetSlider(
                label = "Exact speed",
                valueText = "%.2f×".format(speechRate),
                value = speechRate,
                range = app.xreader.domain.reader
                    .ReaderValidation.SPEECH_RATE_RANGE,
                onChange = { v -> onRateChange(v) }
            )
        }

        SheetSection("Sleep timer") {
            // v1.5.41 (audit item 3): the 10/20/30 picks stay,
            // and the slider beneath sets any length — the
            // timer's model (SleepMode.AfterMinutes) always
            // took a plain minute count, so no storage change.
            // The slider mirrors the active AfterMinutes value
            // when there is one (FocusSheet idiom: presets and
            // slider in sync both ways); a custom length lights
            // no preset chip.
            var customSleepMinutes by remember {
                mutableStateOf(
                    (ttsState.sleepMode as? SleepMode.AfterMinutes)
                        ?.minutes?.toFloat() ?: 20f
                )
            }
            val activeSleepMinutes =
                (ttsState.sleepMode as? SleepMode.AfterMinutes)?.minutes
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
                        onClick = {
                            customSleepMinutes = minutes.toFloat()
                            onSleepChange(SleepMode.AfterMinutes(minutes))
                        },
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
            SheetSlider(
                label = "Exact length",
                valueText =
                    "${(activeSleepMinutes?.toFloat() ?: customSleepMinutes).roundToInt()} min",
                value = activeSleepMinutes?.toFloat()
                    ?: customSleepMinutes,
                range = 5f..120f,
                onChange = { v ->
                    customSleepMinutes = v
                    onSleepChange(
                        SleepMode.AfterMinutes(v.roundToInt())
                    )
                }
            )
            val remaining = ttsState.sleepRemainingMs
            if (remaining != null && ttsState.sleepMode != SleepMode.Off) {
                Text(
                    "Stops in ${formatClock(remaining / 1000.0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Code 122 (S17): the listening cache's own line +
        // Clear — drawn only when the host routed the cache
        // door in (see the parameter note).
        if (onClearListeningCache != null) {
            SheetSection("Listening cache") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        if (listeningCacheBytes != null) {
                            "Saved voices on this phone · " +
                                app.xreader.data.files.StorageMeter
                                    .formatBytes(listeningCacheBytes)
                        } else {
                            "Saved voices on this phone"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearListeningCache) {
                        Text("Clear")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Patch 12: one curated-voice row in the Listen sheet's Voice
 * section — a radio-style row (ring + filled centre when picked)
 * with the language default marked in words. */
@Composable
private fun VoicePickRow(
    label: String,
    note: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(20.dp)
                .border(
                    2.dp,
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            MaterialTheme.colorScheme.primary, CircleShape
                        )
                )
            }
        }
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
    /** MR-4 (R5/R8): the chapters' Contents depth, parallel
     * to [chapters] — FB2 books only today. A size mismatch
     * renders flat. The indent pads the TITLE only (R8:
     * level × 15dp); a chapter is bold when the NEXT chapter
     * is deeper (Moon+'s hasSubChapter seat). */
    chapterLevels: List<Int> = emptyList(),
    currentChapterIndex: Int,
    /** Part 2: the book's own TOC, bridged to chapter
     * positions — when non-empty, the Chapters tab lists
     * THESE entries instead of the raw chapter rows. */
    tocEntries: List<ReaderTocEntry> = emptyList(),
    /** Part 2: the TOC entry the live position has reached
     * (-1 before the first) — the rows' current highlight. */
    currentTocEntryIndex: Int = -1,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    onJumpChapter: (Int) -> Unit,
    onJumpToPosition: (chapterIndex: Int, charOffset: Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDeleteHighlight: (Highlight) -> Unit,
    onEditHighlightNote: (Highlight) -> Unit,
    /** Phase 6: share a highlight as a quote-card PNG. */
    onQuoteCard: (Highlight) -> Unit = {},
    /** Code 122 (S18, audit #17): the current chapter's
     * progress (0f..1f) — the same chapterFraction the reader
     * state already publishes for the chrome. Rendered as the
     * spec's progress line on the current-chapter row only.
     * Null (the default) = the host supplied none and no line
     * is drawn. HOST WIRING: ReaderScreen's TocSheet call must
     * pass state.chapterFraction here. */
    currentChapterProgress: Float? = null
) {
    // Code 120 (§2.11 Reader): in the Material 3 design the
    // sheet opens on the design's Contents-tab knob; Nocturne
    // opens on Chapters exactly as before.
    val designState = app.xreader.ui.theme.LocalDesignState.current
    var tab by remember {
        mutableIntStateOf(
            if (designState.design !=
                app.xreader.data.prefs.AppDesign.NOCTURNE
            ) {
                when (designState.contentsTab) {
                    app.xreader.data.prefs.M3ContentsTab.CHAPTERS -> 0
                    app.xreader.data.prefs.M3ContentsTab.BOOKMARKS -> 1
                    app.xreader.data.prefs.M3ContentsTab.HIGHLIGHTS -> 2
                }
            } else {
                0
            }
        )
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SectionHeader(title = "Contents", eyebrow = "This book",
    modifier = Modifier,
    actionText = null,
    onAction = null,

        )
        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex = tab) {
            // Code 121 (S18): the M3 sheet's tabs are
            // Contents · Bookmarks · Highlights; Nocturne's
            // first tab keeps its shipped "Chapters" name.
            Tab(
                selected = tab == 0, onClick = { tab = 0 },
                text = {
                    Text(
                        if (designState.design !=
                            app.xreader.data.prefs.AppDesign.NOCTURNE
                        ) "Contents" else "Chapters"
                    )
                }
            )
            Tab(
                selected = tab == 1, onClick = { tab = 1 },
                text = { Text("Bookmarks (${bookmarks.size})") }
            )
            Tab(
                selected = tab == 2, onClick = { tab = 2 },
                text = { Text("Highlights (${highlights.size})") }
            )
        }
        // A-121-1 (owner approval, 2026-11-24): in the M3
        // branch only, a Contents list longer than 100 rows
        // gains the range jump bar at its head. The rows
        // counted are the rows shown — the book's own TOC
        // entries when it has them, else the chapter rows.
        // Nocturne and short lists render exactly as before
        // (the tab-0 list state is simply hoisted; its
        // behaviour is unchanged).
        val contentsRowCount = if (tocEntries.isNotEmpty()) {
            tocEntries.size
        } else {
            chapters.size
        }
        val contentsGrouped =
            designState.design !=
                app.xreader.data.prefs.AppDesign.NOCTURNE &&
                contentsRowCount > CHAPTER_RANGE_THRESHOLD
        val contentsListState = rememberLazyListState()
        val contentsScope = rememberCoroutineScope()
        val contentsRange by remember(contentsRowCount) {
            derivedStateOf {
                (contentsListState.firstVisibleItemIndex /
                    CHAPTER_RANGE_SIZE).coerceIn(
                    0,
                    ((contentsRowCount - 1) /
                        CHAPTER_RANGE_SIZE).coerceAtLeast(0)
                )
            }
        }
        when (tab) {
            0 -> Column {
                if (contentsGrouped) {
                    ChapterRangeBar(
                        totalCount = contentsRowCount,
                        selectedRange = contentsRange,
                        onSelectRange = { range ->
                            contentsScope.launch {
                                contentsListState.scrollToItem(
                                    range * CHAPTER_RANGE_SIZE
                                )
                            }
                        }
                    )
                }
                LazyColumn(
                    state = contentsListState,
                    modifier = Modifier.heightIn(max = 480.dp)
                ) {
                // Micro-build M: the chapter's AI summary no
                // longer rides at the top of Contents (the
                // owner's ruling — Contents is the book's own
                // list, nothing else; summaries live on the
                // book page and in the AI sheet).
                if (tocEntries.isNotEmpty()) {
                    // Part 2: the book's own Contents — the
                    // TOC's entries in the book's order. Each
                    // row carries its target segment's state
                    // (the read tick, the "saved" word) exactly
                    // as chapter rows render it, is highlighted
                    // at the entry the live position has
                    // reached, and jumps to the entry's own
                    // position — fragment offset included.
                    itemsIndexed(
                        tocEntries,
                        key = { index, _ -> "toc-$index" }
                    ) { index, entry ->
                        val segment =
                            chapters.getOrNull(entry.chapterIndex)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onJumpToPosition(
                                        entry.chapterIndex,
                                        entry.charOffset
                                    )
                                }
                                // Code 121 (S18): the current
                                // chapter sits in
                                // secondaryContainer under M3.
                                .background(
                                    if (designState.design !=
                                        app.xreader.data.prefs
                                            .AppDesign.NOCTURNE &&
                                        index == currentTocEntryIndex
                                    ) {
                                        MaterialTheme.colorScheme
                                            .secondaryContainer
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .padding(
                                    horizontal = 8.dp, vertical = 12.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                                modifier = Modifier
                                    .size(width = 40.dp, height = 20.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (
                                        index == currentTocEntryIndex
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // Code 122 (S18): the current
                                // entry's progress line.
                                if (index == currentTocEntryIndex &&
                                    currentChapterProgress != null
                                ) {
                                    Text(
                                        "${(currentChapterProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography
                                            .bodySmall,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    )
                                }
                            }
                            if (segment?.read == true) {
                                Icon(
                                    Icons.Filled.Check, "Read",
                                    tint = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            if (segment?.contentPath != null) {
                                Text(
                                    "saved",
                                    style = MaterialTheme.typography
                                        .labelSmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                )
                            }
                        }
                    }
                } else {
                // MR-4 (R8): levels apply only when parallel.
                val levels = if (chapterLevels.size == chapters.size) {
                    chapterLevels
                } else {
                    emptyList()
                }
                itemsIndexed(chapters, key = { _, ch -> ch.id }) { index, chapter ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onJumpChapter(index) }
                            .background(
                                if (designState.design !=
                                    app.xreader.data.prefs
                                        .AppDesign.NOCTURNE &&
                                    index == currentChapterIndex
                                ) {
                                    MaterialTheme.colorScheme
                                        .secondaryContainer
                                } else {
                                    Color.Transparent
                                }
                            )
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(width = 40.dp, height = 20.dp)
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                // R8: the indent pads the
                                // TITLE only — level × 15dp.
                                .padding(
                                    start = ((levels.getOrNull(index) ?: 0) * 15).dp
                                )
                        ) {
                            Text(
                                chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (index == currentChapterIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                // MR-4 (R8): bold when the next
                                // chapter sits deeper (it has
                                // sub-chapters).
                                fontWeight = if (
                                    (levels.getOrNull(index + 1) ?: 0) >
                                    (levels.getOrNull(index) ?: 0)
                                ) {
                                    FontWeight.Bold
                                } else {
                                    null
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Code 122 (S18): the current
                            // chapter's progress line.
                            if (index == currentChapterIndex &&
                                currentChapterProgress != null
                            ) {
                                Text(
                                    "${(currentChapterProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                            }
                        }
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
                        // Code 121 (S18): a highlight shows its
                        // stored colour as a dot — the colour is
                        // data, rendered as picked in both
                        // designs (trace T6).
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    Color(highlightTintForToken(highlight.color))
                                )
                        )
                        Spacer(Modifier.width(10.dp))
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

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class
)
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
    // Code 121 (S22): under M3 the sheet's form is the
    // expressive time picker + the wavy progress ring; the
    // Nocturne form below is untouched.
    val focusIsM3 = app.xreader.ui.theme.LocalDesignState.current.design !=
        app.xreader.data.prefs.AppDesign.NOCTURNE
    val focusTimePickerState =
        rememberTimePickerState(
            initialHour = 0, initialMinute = 25, is24Hour = true
        )
    val focusPickerMinutes =
        focusTimePickerState.hour * 60 + focusTimePickerState.minute
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
                if (focusIsM3) {
                    TimePicker(
                        state = focusTimePickerState,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                } else {
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
                }
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
                    onClick = {
                        onStart(
                            if (focusIsM3) {
                                focusPickerMinutes.coerceIn(1, 120)
                            } else {
                                customMinutes.roundToInt()
                            },
                            soundscape
                        )
                    },
                    modifier = Modifier,
                    enabled = true,

                )
            }
            app.xreader.domain.reader.FocusState.RUNNING,
            app.xreader.domain.reader.FocusState.PAUSED -> {
                if (focusIsM3) {
                    // S22: the running state is the wavy ring
                    // with the remaining time at its centre.
                    Box(
                        Modifier
                            .size(160.dp)
                            .align(Alignment.CenterHorizontally),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularWavyProgressIndicator(
                            progress = {
                                    val total =
                                        focus.durationMinutes * 60_000f
                                    if (total > 0f) {
                                        (focus.remainingMs / total)
                                            .coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier.size(160.dp)
                            )
                        Text(
                            formatClock(focus.remainingMs / 1000.0),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                } else {
                    Text(
                        formatClock(focus.remainingMs / 1000.0),
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
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
