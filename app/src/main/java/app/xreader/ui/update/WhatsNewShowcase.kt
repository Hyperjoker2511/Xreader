package app.xreader.ui.update

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.xreader.data.prefs.DesignState
import app.xreader.ui.theme.M3ThemeRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Code 121 (R39): this file carries TWO showcases, kept apart
 * by design:
 *
 * 1. [WhatsNewReveal] — the reveal for a DESIGN-CARRYING
 *    update (an update adding a design the installed app did
 *    not previously offer; for this build, the Material 3
 *    design arriving in code 121). It wears the NEW design
 *    itself — composed inside [M3ThemeRoot] regardless of the
 *    active design — it is TRULY MODAL (a [Dialog]; touches
 *    cannot reach the shell beneath it), and its seen-record
 *    is [RevealPrefs], written only by its own terminal
 *    actions (Try / Keep mine / Skip / finishing the last
 *    card) and read only by its gate in MainActivity.
 *
 * 2. [WhatsNewShowcase] — the legacy showcase for updates
 *    that carry NO new design. It wears the ACTIVE design, as
 *    before, and keeps the GamePrefs seen-store it has always
 *    shared with the Me note. It is gated separately in
 *    MainActivity and never touches the reveal's record.
 */

// ── The reveal (R39) ─────────────────────────────────────────────

/**
 * The design-carrying update reveal. One card per feature:
 * a filled M3 card on the M3 surface, a shape-morph marker,
 * the headline in emphasized headlineSmall, one short line,
 * and a small LIVE demo built from real M3 components. The
 * final card is the choice card: "Try the new design"
 * (filled) switches the design via the caller's DesignPrefs
 * write — the only door besides Customise that may, and only
 * on this tap — "Keep mine" (tonal) dismisses, and Skip is
 * present on EVERY card from the first. It never switches
 * anyone automatically.
 *
 * [designState] is the CURRENT design state; the reveal
 * composes the M3 theme from it (so a colour the user already
 * picked for M3 is honoured) while forcing the design to
 * Material 3 inside the theme only — the stored design is
 * untouched unless Try is tapped.
 *
 * [onTryDesign] must switch the design (DesignPrefs) and
 * then finish; [onFinish] records the reveal seen
 * ([RevealPrefs]) and dismisses. Both are terminal actions;
 * Skip and finishing the last card also route to [onFinish].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WhatsNewReveal(
    designState: DesignState,
    onTryDesign: () -> Unit,
    onFinish: () -> Unit
) {
    val features = remember {
        listOf(
            RevealFeature(
                title = "A whole new design",
                body = "This is Material 3 — Google's design " +
                    "language, in your phone's colours or any " +
                    "colour you pick. You are looking at it now.",
                demo = RevealDemo.NAV_BAR
            ),
            RevealFeature(
                title = "Tools that float, bars that dock",
                body = "Reading tools ride in a floating " +
                    "toolbar; the five tabs live in a docked " +
                    "bar with their names always showing.",
                demo = RevealDemo.TOOLBAR
            ),
            RevealFeature(
                title = "Your colour, everywhere",
                body = "One colour bar sets the whole scheme. " +
                    "Try it — this preview recolours as you pick.",
                demo = RevealDemo.COLOUR_BAR
            )
        )
    }
    // The reveal wears the NEW design: the M3 theme resolved
    // from the user's own M3 state, with the design forced to
    // Material 3 for this subtree only.
    val previewState = remember(designState) {
        designState.copy(
            design = app.xreader.data.prefs.AppDesign.MATERIAL3
        )
    }
    var previewSeed by remember { mutableStateOf(designState.seedArgb) }
    var previewSource by remember { mutableStateOf(designState.colourSource) }
    val liveState = remember(previewState, previewSeed, previewSource) {
        previewState.copy(seedArgb = previewSeed, colourSource = previewSource)
    }
    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        M3ThemeRoot(design = liveState, reduceMotion = false) {
            RevealPager(
                features = features,
                seedArgb = previewSeed,
                colourSource = previewSource,
                onPickDynamic = {
                    previewSource =
                        app.xreader.data.prefs.M3ColourSource.DYNAMIC
                },
                onPickSeed = { argb ->
                    previewSeed = argb
                    previewSource =
                        app.xreader.data.prefs.M3ColourSource.PICKED
                },
                onTryDesign = onTryDesign,
                onFinish = onFinish
            )
        }
    }
}

private data class RevealFeature(
    val title: String,
    val body: String,
    val demo: RevealDemo
)

private enum class RevealDemo { NAV_BAR, TOOLBAR, COLOUR_BAR }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RevealPager(
    features: List<RevealFeature>,
    seedArgb: Long?,
    colourSource: app.xreader.data.prefs.M3ColourSource,
    onPickDynamic: () -> Unit,
    onPickSeed: (Long) -> Unit,
    onTryDesign: () -> Unit,
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(
        pageCount = { features.size + 1 }
    )
    val scope = rememberCoroutineScope()
    val lastPage = features.size
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "What's new",
                    style = MaterialTheme.typography.headlineSmall
                )
                // Skip on EVERY card, from the first (R39).
                TextButton(onClick = onFinish) { Text("Skip") }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout
                    .PaddingValues(vertical = 16.dp),
                pageSpacing = 16.dp
            ) { page ->
                if (page < features.size) {
                    val feature = features[page]
                    Card(
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme
                                    .surfaceContainer
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(
                                        MaterialTheme.colorScheme
                                            .surfaceContainerHigh
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                when (feature.demo) {
                                    RevealDemo.NAV_BAR ->
                                        RevealNavBarDemo()
                                    RevealDemo.TOOLBAR ->
                                        RevealToolbarDemo()
                                    RevealDemo.COLOUR_BAR ->
                                        RevealColourBarDemo(
                                            seedArgb = seedArgb,
                                            colourSource = colourSource,
                                            onPickDynamic = onPickDynamic,
                                            onPickSeed = onPickSeed
                                        )
                                }
                            }
                            Column(
                                Modifier.padding(20.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment =
                                        Alignment.CenterVertically,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(10.dp)
                                ) {
                                    // The card's one shape-morph
                                    // marker (§2.3's sparing use).
                                    Box(
                                        Modifier
                                            .size(18.dp)
                                            .clip(
                                                MaterialShapes
                                                    .Cookie9Sided
                                                    .toShape()
                                            )
                                            .background(
                                                MaterialTheme.colorScheme
                                                    .primary
                                            )
                                    )
                                    Text(
                                        feature.title,
                                        style = MaterialTheme
                                            .typography
                                            .headlineSmallEmphasized
                                    )
                                }
                                Text(
                                    feature.body,
                                    style = MaterialTheme.typography
                                        .bodyMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // The choice card (R39): the only card
                    // that can switch the design, and only
                    // on the user's tap.
                    Card(
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme
                                    .surfaceContainer
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(12.dp),
                            horizontalAlignment =
                                Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(
                                        MaterialShapes.Cookie9Sided
                                            .toShape()
                                    )
                                    .background(
                                        MaterialTheme.colorScheme
                                            .primaryContainer
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme
                                        .onPrimaryContainer
                                )
                            }
                            Text(
                                "This is the new Material 3 design.",
                                style = MaterialTheme.typography
                                    .headlineSmallEmphasized
                            )
                            Text(
                                "Try it now, or keep the design " +
                                    "you have. You can switch any " +
                                    "time in Customise → Design — " +
                                    "nothing changes unless you tap.",
                                style = MaterialTheme.typography
                                    .bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            ButtonGroup(
                                overflowIndicator = {},
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                customItem(
                                    buttonGroupContent = {
                                        Button(
                                            onClick = onTryDesign,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                        ) {
                                            Text("Try the new design")
                                        }
                                    },
                                    menuContent = {}
                                )
                                customItem(
                                    buttonGroupContent = {
                                        FilledTonalButton(
                                            onClick = onFinish,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                        ) {
                                            Text("Keep mine")
                                        }
                                    },
                                    menuContent = {}
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..lastPage).forEach { index ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (index ==
                                        pagerState.currentPage
                                    ) {
                                        MaterialTheme.colorScheme
                                            .primary
                                    } else {
                                        MaterialTheme.colorScheme
                                            .surfaceContainerHigh
                                    },
                                    CircleShape
                                )
                        )
                    }
                }
                if (pagerState.currentPage < lastPage) {
                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    pagerState.currentPage + 1
                                )
                            }
                        }
                    ) { Text("Next") }
                } else {
                    // Finishing the last card without a choice
                    // is a terminal action: it records the
                    // reveal seen, exactly like Keep mine.
                    TextButton(onClick = onFinish) { Text("Done") }
                }
            }
        }
    }
}

/** Live demo: the REAL flexible navigation bar component —
 * five labelled destinations, the selection moving on a tap.
 * It is a demo instance only; it navigates nothing. */
@Composable
private fun RevealNavBarDemo() {
    var selected by remember { mutableIntStateOf(0) }
    val items = listOf(
        Triple("Library", Icons.Filled.Home, 0),
        Triple("Explore", Icons.Filled.Search, 1),
        Triple("History", Icons.Filled.MenuBook, 2),
        Triple("Downloads", Icons.Filled.Settings, 3),
        Triple("Profile", Icons.Filled.Person, 4)
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        NavigationBar {
            items.forEach { (label, icon, index) ->
                NavigationBarItem(
                    selected = selected == index,
                    onClick = { selected = index },
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label) }
                )
            }
        }
        Text(
            "Tap the destinations — this is the real bar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Live demo: the REAL floating toolbar component, expanded,
 * carrying reader-style icon actions. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RevealToolbarDemo() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalFloatingToolbar(expanded = true) {
            IconButton(onClick = {}) {
                Icon(Icons.Filled.MenuBook, contentDescription = "Contents")
            }
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        Text(
            "The reader's toolbar floats over the page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Live demo: THE colour bar (§2.1.1) — W3's shared
 * [app.xreader.ui.common.ColourBar] in scheme mode
 * (D-121-W7-01 retired by W8: the local copy is deleted).
 * Picking recolours this reveal's preview theme in place.
 */
@Composable
private fun RevealColourBarDemo(
    seedArgb: Long?,
    colourSource: app.xreader.data.prefs.M3ColourSource,
    onPickDynamic: () -> Unit,
    onPickSeed: (Long) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        app.xreader.ui.common.ColourBar(
            selection = when (colourSource) {
                app.xreader.data.prefs.M3ColourSource.DYNAMIC ->
                    app.xreader.ui.common.ColourBarSelection.Dynamic
                app.xreader.data.prefs.M3ColourSource.PICKED ->
                    seedArgb?.let {
                        app.xreader.ui.common.ColourBarSelection.Seed(it)
                    }
            },
            onSelect = { picked ->
                when (picked) {
                    app.xreader.ui.common.ColourBarSelection.Dynamic ->
                        onPickDynamic()
                    is app.xreader.ui.common.ColourBarSelection.Seed ->
                        onPickSeed(picked.argb)
                    app.xreader.ui.common.ColourBarSelection.ThemeColour ->
                        Unit
                }
            }
        )
        Text(
            "Pick a colour — the preview above recolours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── The legacy showcase (non-design-carrying updates) ────────────

/**
 * The showcase for an update that brings NO new design: it
 * wears the ACTIVE design, as it always has, and finishing
 * or skipping records the version in the GamePrefs store it
 * shares with the Me note (MainActivity's legacy gate). It
 * never reads or writes the reveal's [RevealPrefs] record.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WhatsNewShowcase(onFinish: () -> Unit) {
    val features = remember {
        listOf(
            ShowcaseFeature(
                title = "PDF selection, fixed",
                body = "You can select and copy words in a PDF " +
                    "again — even zoomed right in, the way you " +
                    "actually read.",
                demo = ShowcaseDemo.PDF_SELECTION
            ),
            ShowcaseFeature(
                title = "Selection follows your colours",
                body = "The highlight colour now comes from " +
                    "your design's colours, and the colour row " +
                    "on the selection bar tints with it.",
                demo = ShowcaseDemo.SELECTION_COLOURS
            )
        )
    }
    val pagerState = rememberPagerState(pageCount = { features.size })
    val scope = rememberCoroutineScope()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                "What's new",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout
                    .PaddingValues(vertical = 16.dp),
                pageSpacing = 16.dp
            ) { page ->
                val feature = features[page]
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme.colorScheme
                                .surfaceContainer
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(
                                    MaterialTheme.colorScheme
                                        .surfaceContainerHigh
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            when (feature.demo) {
                                ShowcaseDemo.PDF_SELECTION ->
                                    PdfSelectionDemo()
                                ShowcaseDemo.SELECTION_COLOURS ->
                                    SelectionColoursDemo()
                            }
                        }
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                feature.title,
                                style = MaterialTheme.typography
                                    .titleLargeEmphasized
                            )
                            Text(
                                feature.body,
                                style = MaterialTheme.typography
                                    .bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onFinish) { Text("Skip") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    features.indices.forEach { index ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (index ==
                                        pagerState.currentPage
                                    ) {
                                        MaterialTheme.colorScheme
                                            .primary
                                    } else {
                                        MaterialTheme.colorScheme
                                            .surfaceContainerHigh
                                    },
                                    CircleShape
                                )
                        )
                    }
                }
                Button(
                    onClick = {
                        if (pagerState.currentPage <
                            features.lastIndex
                        ) {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    pagerState.currentPage + 1
                                )
                            }
                        } else {
                            onFinish()
                        }
                    }
                ) {
                    Text(
                        if (pagerState.currentPage <
                            features.lastIndex
                        ) {
                            "Next"
                        } else {
                            "Done"
                        }
                    )
                }
            }
        }
    }
}

private data class ShowcaseFeature(
    val title: String,
    val body: String,
    val demo: ShowcaseDemo
)

private enum class ShowcaseDemo {
    PDF_SELECTION, SELECTION_COLOURS
}

/** Demo: PDF selection while zoomed — a page at reading zoom,
 * a selection wash growing across one word with a handle at
 * each end, on a loop. The wash is the scheme's primary at
 * the selection spec alpha. */
@Composable
private fun PdfSelectionDemo() {
    val transition = rememberInfiniteTransition(label = "pdfDemo")
    val grow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pdfDemoGrow"
    )
    val wash = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    Box(
        Modifier
            .size(width = 250.dp, height = 210.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .scale(1.6f)
                .width(210.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            listOf(150, 168, 132).forEach { widthDp ->
                Box(
                    Modifier
                        .width(widthDp.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(line)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .width(38.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(line)
                )
                Box {
                    Box(
                        Modifier
                            .width(64.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(line)
                    )
                    Box(
                        Modifier
                            .width((64 * grow).dp)
                            .height(13.dp)
                            .offset(y = (-3).dp)
                            .background(wash)
                    )
                    if (grow > 0.05f) {
                        Box(
                            Modifier
                                .offset(x = (-4).dp, y = 8.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.primary
                                )
                        )
                        Box(
                            Modifier
                                .offset(
                                    x = (64 * grow - 3).dp,
                                    y = 8.dp
                                )
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.primary
                                )
                        )
                    }
                }
                Box(
                    Modifier
                        .width(46.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(line)
                )
            }
            listOf(160, 140, 166).forEach { widthDp ->
                Box(
                    Modifier
                        .width(widthDp.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(line)
                )
            }
        }
    }
}

/** Demo: selection colours follow the theme — a line of
 * "selected" text whose wash cycles through three colours,
 * and a mock selection bar whose colour row tints to match. */
@Composable
private fun SelectionColoursDemo() {
    val scheme = MaterialTheme.colorScheme
    val colours = remember(scheme) {
        listOf(scheme.primary, scheme.tertiary, scheme.secondary)
    }
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1400)
            step = (step + 1) % colours.size
        }
    }
    val tint by animateColorAsState(
        targetValue = colours[step],
        animationSpec = tween(durationMillis = 500),
        label = "selectionTint"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(tint.copy(alpha = 0.18f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            colours.forEachIndexed { index, colour ->
                Box(
                    Modifier
                        .size(if (index == step) 24.dp else 18.dp)
                        .clip(CircleShape)
                        .background(colour)
                )
            }
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier
                    .size(width = 30.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurface
                            .copy(alpha = 0.4f)
                    )
            )
            Box(
                Modifier
                    .size(width = 22.dp, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurface
                            .copy(alpha = 0.4f)
                    )
            )
        }
        Box(contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(52, 74, 44, 62).forEach { widthDp ->
                    Box(
                        Modifier
                            .width(widthDp.dp)
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.28f)
                            )
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(tint.copy(alpha = 0.38f))
            )
        }
        Text(
            "One tap recolours the highlight",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
