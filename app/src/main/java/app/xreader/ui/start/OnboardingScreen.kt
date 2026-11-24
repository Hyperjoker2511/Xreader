package app.xreader.ui.start

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.sharedAxisXEnter
import app.xreader.core.motion.sharedAxisXExit
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.ScreenHeader

/**
 * First-run onboarding (PLAN §3): three plain pages, all skippable.
 * Page changes ride the engine's Shared Axis (single motion source,
 * Amendment 5) — no local animation constants.
 *
 * Nocturne reskin (§5.26): each page's hero sits on the HaloStage
 * recipe from the book page (320dp primary-16% radial top-centre +
 * 240dp tertiary-13%), page dots replace the "1 of 3" counter, and
 * there is no dock (as today). Colours come from Material roles
 * only.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    /** Code 120 (design §2.9 S38): the first-start design &
     * colour ask lives on one page of this pager. It is shown
     * once per fresh install (this screen is unreachable after
     * onboarding completes); priming has already seeded the
     * Material 3 default, so skipping the ask — Skip is always
     * visible below — lands on Material 3 untouched. */
    designState: app.xreader.data.prefs.DesignState =
        app.xreader.data.prefs.DesignState(),
    onPickDesign: (app.xreader.data.prefs.AppDesign) -> Unit = {},
    onPickDynamicColour: () -> Unit = {},
    onPickSeedColour: (Long) -> Unit = {},
    /** Code 120 completion (S38): the Nocturne card's own
     * accent choice — the preset scheme names, one tap. The
     * shell passes the live appearance scheme and writes picks
     * through AppearancePrefs.setColourScheme (the one
     * explicit scheme-choice write). */
    nocturneScheme: String = "Nocturne violet",
    onPickNocturneScheme: (String) -> Unit = {}
) {
    var page by remember { mutableIntStateOf(0) }
    // The ask is page 1; the three text pages keep their order
    // around it (welcome, ask, sources, companion).
    val pageCount = PAGES.size + 1
    val last = pageCount - 1
    // v1.0.2 (R01): themed Surface root — before, this bare Column had
    // no LocalContentColor provider, so colour-less text rendered in
    // the default black over the dark window (the Recovery defect).
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize()
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        ScreenHeader(title = "Welcome to Xreader", eyebrow = "First run")
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                ContentTransform(
                    sharedAxisXEnter(reduceMotion = false),
                    sharedAxisXExit(reduceMotion = false)
                )
            },
            label = "onboardingPage",
            modifier = Modifier.weight(1f)
        ) { current ->
            if (current == 1) {
                DesignAskPage(
                    designState = designState,
                    onPickDesign = onPickDesign,
                    onPickDynamicColour = onPickDynamicColour,
                    onPickSeedColour = onPickSeedColour,
                    nocturneScheme = nocturneScheme,
                    onPickNocturneScheme = onPickNocturneScheme
                )
            } else {
            val textPage = if (current == 0) 0 else current - 1
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-48).dp)
                        .size(320.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary
                                        .copy(alpha = 0.16f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-64).dp, y = 150.dp)
                        .size(240.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MaterialTheme.colorScheme.tertiary
                                        .copy(alpha = 0.13f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        PAGES[textPage].first,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        PAGES[textPage].second,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
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
            TextButton(onClick = onDone) { Text("Skip") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until pageCount).forEach { index ->
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (index == page) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                CircleShape
                            )
                    )
                }
            }
            PrimaryPill(
                text = if (page == last) "Get started" else "Next",
                enabled = true,
                onClick = { if (page == last) onDone() else page++ }
            )
        }
    }
    }
}

/**
 * The first-start ask (S38): two design cards, each wearing its
 * own design's colours — Nocturne in Nocturne's scheme, Material
 * 3 in the scheme it resolves right now (dynamic first) — and,
 * when Material 3 is picked, its colour choice in the same
 * breath: the phone's dynamic colour, or any colour through the
 * S37 wheel dialog. Nothing here is a gate: Skip and Next are
 * always live in the pager chrome around this page.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DesignAskPage(
    designState: app.xreader.data.prefs.DesignState,
    onPickDesign: (app.xreader.data.prefs.AppDesign) -> Unit,
    onPickDynamicColour: () -> Unit,
    onPickSeedColour: (Long) -> Unit,
    nocturneScheme: String,
    onPickNocturneScheme: (String) -> Unit
) {
    val context = LocalContext.current
    val m3Dark = app.xreader.ui.theme.m3DarkTheme(designState)
    val m3Colors = remember(context, designState, m3Dark) {
        app.xreader.ui.theme.resolveM3ColorScheme(context, designState, m3Dark)
    }
    val nocturneColors = remember {
        app.xreader.ui.theme.appSchemeNamed("Nocturne violet").colors(true)
    }
    var showWheel by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Make it yours",
            style = MaterialTheme.typography.headlineMediumEmphasized
        )
        Text(
            "Xreader has two designs. Pick the one you like — " +
                "you can switch any time in Customise.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DesignAskCard(
                name = "Nocturne",
                line = "Violet on black glass.",
                colors = nocturneColors,
                selected = designState.design ==
                    app.xreader.data.prefs.AppDesign.NOCTURNE,
                modifier = Modifier.weight(1f)
            ) {
                onPickDesign(app.xreader.data.prefs.AppDesign.NOCTURNE)
            }
            DesignAskCard(
                name = "Material 3",
                line = "Google's design, your colours.",
                colors = m3Colors,
                selected = designState.design ==
                    app.xreader.data.prefs.AppDesign.MATERIAL3,
                modifier = Modifier.weight(1f)
            ) {
                onPickDesign(app.xreader.data.prefs.AppDesign.MATERIAL3)
            }
        }
        if (designState.design == app.xreader.data.prefs.AppDesign.NOCTURNE) {
            // Code 120 completion (S38): Nocturne's own accent
            // choice — its preset schemes, one tap each, the
            // picked scheme's primary riding as the swatch.
            Text(
                "Accent",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 24.dp)
            )
            val schemePrimaries = remember {
                app.xreader.data.prefs.AppearanceValidation
                    .COLOUR_SCHEMES.associateWith { schemeName ->
                        app.xreader.ui.theme.appSchemeNamed(schemeName)
                            .colors(true).primary
                    }
            }
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                app.xreader.data.prefs.AppearanceValidation
                    .COLOUR_SCHEMES.forEach { schemeName ->
                        FilterChip(
                            selected = nocturneScheme == schemeName,
                            onClick = {
                                onPickNocturneScheme(schemeName)
                            },
                            label = {
                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(6.dp),
                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .size(12.dp)
                                            .background(
                                                schemePrimaries[
                                                    schemeName
                                                ]
                                                    ?: Color.Unspecified,
                                                CircleShape
                                            )
                                    )
                                    Text(schemeName)
                                }
                            }
                        )
                    }
            }
        }
        if (designState.design == app.xreader.data.prefs.AppDesign.MATERIAL3) {
            Text(
                "Colour",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 24.dp)
            )
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = designState.colourSource ==
                        app.xreader.data.prefs.M3ColourSource.DYNAMIC,
                    onClick = onPickDynamicColour,
                    label = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                m3Colors.primary, m3Colors.secondary,
                                m3Colors.tertiary
                            ).forEach { swatch ->
                                Box(
                                    Modifier
                                        .size(12.dp)
                                        .background(swatch, CircleShape)
                                )
                            }
                            Text("Dynamic colour")
                        }
                    }
                )
                FilterChip(
                    selected = designState.colourSource ==
                        app.xreader.data.prefs.M3ColourSource.PICKED,
                    onClick = { showWheel = true },
                    label = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            designState.seedArgb?.let { seed ->
                                Box(
                                    Modifier
                                        .size(12.dp)
                                        .background(Color(seed), CircleShape)
                                )
                            }
                            Text("Pick a colour")
                        }
                    }
                )
            }
        }
        Text(
            "You can change all of this later in Customise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp)
        )
    }
    if (showWheel) {
        app.xreader.ui.common.ColourWheelPickerDialog(
            title = "Pick a colour",
            initialArgb = designState.seedArgb
                ?: (m3Colors.primary.toArgb().toLong() and 0xFFFFFFFFL),
            onDismiss = { showWheel = false },
            onPick = { argb ->
                onPickSeedColour(argb)
                showWheel = false
            }
        )
    }
}

/** One design card on the ask page: a mini preview wearing the
 * design's own scheme (bar pill + shelf lines over its
 * background), its name and one line — geometry per S38 (card
 * largeIncreased, preview small). */
@Composable
private fun DesignAskCard(
    name: String,
    line: String,
    colors: androidx.compose.material3.ColorScheme,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainer)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(86.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.background)
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.surfaceContainerHigh)
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .fillMaxWidth(0.55f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.primary)
            )
        }
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        if (selected) {
            Text(
                "In use",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private val PAGES = listOf(
    "Your books, your files" to
        "Bring the novels and e-books you already have — EPUB, PDF, TXT " +
        "and more — and read them in one calm, beautiful reader.",
    "Your sources, your library" to
        "Add the reading sources and library servers you use, download " +
        "chapters for offline reading, and pick up on any device where " +
        "you left off.",
    "A reading companion, when you want it" to
        "With a free Gemini key, Xreader can summarise chapters, keep " +
        "track of characters and terms, and translate raw chapters. " +
        "Entirely optional — add the key any time in More."
)
