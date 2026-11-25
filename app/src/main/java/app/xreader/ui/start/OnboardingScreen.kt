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
    onPickNocturneScheme: (String) -> Unit = {},
    /** Code 121 (S38/§5.2 parity): the colour bar serving the
     * Nocturne card — the picked colour feeds Nocturne's
     * any-colour generation (the shell writes it as the
     * "Custom" scheme entry). */
    onPickNocturneColour: (Long) -> Unit = {},
    /** Code-129 motion pass: the caller's motion decision
     * (the same animationsEnabled the launch intro receives —
     * system animator scale AND the Appearance reduce-motion
     * preference, via SpringMotionEngine). Before this pass
     * the pager hardcoded reduceMotion = false and animated
     * at full travel even for reduce-motion users. */
    reduceMotion: Boolean = false
) {
    var page by remember { mutableIntStateOf(0) }
    // The ask is page 1; the three text pages keep their order
    // around it (welcome, ask, sources, companion).
    val pageCount = PAGES.size + 1
    val last = pageCount - 1
    // Code 127 (audit G2): the pager chrome wears the primed
    // design — under a Material 3 prime the text pages carry
    // no halo blooms and the primary action is the stock
    // Button; the halo + PrimaryPill are Nocturne's chrome.
    // (DesignAskPage itself stays the deliberate neutral
    // exception, as settled.)
    val editorialPrimed = designState.design ==
        app.xreader.data.prefs.AppDesign.EDITORIAL
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
                    sharedAxisXEnter(reduceMotion = reduceMotion),
                    sharedAxisXExit(reduceMotion = reduceMotion)
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
                    onPickNocturneScheme = onPickNocturneScheme,
                    onPickNocturneColour = onPickNocturneColour
                )
            } else {
            val textPage = if (current == 0) 0 else current - 1
            Box(Modifier.fillMaxSize()) {
                if (!editorialPrimed) {
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
                }
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
            if (editorialPrimed) {
                androidx.compose.material3.Button(
                    onClick = {
                        if (page == last) onDone() else page++
                    }
                ) {
                    Text(if (page == last) "Get started" else "Next")
                }
            } else {
                PrimaryPill(
                    text = if (page == last) "Get started" else "Next",
                    enabled = true,
                    onClick = { if (page == last) onDone() else page++ }
                )
            }
        }
    }
    }
}

/**
 * The first-start ask (S38, code 121 form; three cards since
 * code 123): "Pick your design" — three cards in the active
 * scheme's language. The Editorial card is pre-selected
 * (priming seeds the new-user default; this page only records
 * the user's hand). The Nocturne card is TEXT ONLY — its name
 * and a one-line description in scheme colours; its colours
 * are never drawn and there is NO violet miniature (trace
 * T1). Beneath the cards, ONE colour bar serves the two
 * colour-following designs: for Material 3 the pick seeds
 * the M3 scheme; for Nocturne it feeds that design's
 * any-colour generation (§5.2). Editorial wears its own
 * colours, so under an Editorial pick the bar gives way to
 * one plain line saying so (the owner's colour ruling,
 * 2026-11-24). Skip lives in the pager chrome around this
 * page and is always visible; skipping lands on Editorial,
 * opening on Library.
 */
@Composable
private fun DesignAskPage(
    designState: app.xreader.data.prefs.DesignState,
    onPickDesign: (app.xreader.data.prefs.AppDesign) -> Unit,
    onPickDynamicColour: () -> Unit,
    onPickSeedColour: (Long) -> Unit,
    nocturneScheme: String,
    onPickNocturneScheme: (String) -> Unit,
    onPickNocturneColour: (Long) -> Unit
) {
    // W8 (D-121-W7-01 retired): the ask's bar is W3's shared
    // ColourBar. A Nocturne-destination pick is held locally
    // for the bar's selected state; the write itself goes
    // through onPickNocturneColour (the §5.2 "Custom" entry).
    var nocturnePicked by remember { mutableStateOf<Long?>(null) }
    // Code 124: the design named Material 3 is the EDITORIAL
    // implementation (the separate MATERIAL3 design is
    // removed); the ask's colour bar serves it exactly as it
    // served the old M3 pick.
    val isEditorial = designState.design ==
        app.xreader.data.prefs.AppDesign.EDITORIAL
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Pick your design",
            style = MaterialTheme.typography.headlineMediumEmphasized
        )
        Text(
            "You can switch any time in Customise → Design.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DesignAskCard(
                name = "Material 3",
                line = "Paper-warm and editorial, with a serif " +
                    "voice — in your phone's colours, or any " +
                    "colour you pick.",
                selected = isEditorial,
                modifier = Modifier.fillMaxWidth()
            ) {
                onPickDesign(
                    app.xreader.data.prefs.AppDesign.EDITORIAL
                )
            }
            DesignAskCard(
                name = "Nocturne",
                line = "A dark glass design with a violet " +
                    "accent, built around your library's covers.",
                selected = designState.design ==
                    app.xreader.data.prefs.AppDesign.NOCTURNE,
                modifier = Modifier.fillMaxWidth()
            ) {
                onPickDesign(
                    app.xreader.data.prefs.AppDesign.NOCTURNE
                )
            }
        }
        Text(
            "Pick a colour — it applies to the design you chose.",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 24.dp)
        )
        // The one colour bar (§2.1.1), serving both designs:
        // W3's shared ColourBar in scheme mode. For Material 3
        // a pick seeds its scheme (Dynamic returns to the
        // phone's colour); for Nocturne a seed/pick-any colour
        // feeds §5.2's "Custom" generation. Dynamic has no
        // Nocturne meaning and is inert there.
        app.xreader.ui.common.ColourBar(
            selection = if (isEditorial) {
                when (designState.colourSource) {
                    app.xreader.data.prefs.M3ColourSource.DYNAMIC ->
                        app.xreader.ui.common.ColourBarSelection.Dynamic
                    app.xreader.data.prefs.M3ColourSource.PICKED ->
                        designState.seedArgb?.let {
                            app.xreader.ui.common.ColourBarSelection
                                .Seed(it)
                        }
                }
            } else {
                nocturnePicked?.let {
                    app.xreader.ui.common.ColourBarSelection.Seed(it)
                }
            },
            onSelect = { picked ->
                when (picked) {
                    app.xreader.ui.common.ColourBarSelection.Dynamic -> {
                        if (isEditorial) onPickDynamicColour()
                    }
                    is app.xreader.ui.common.ColourBarSelection.Seed -> {
                        if (isEditorial) {
                            onPickSeedColour(picked.argb)
                        } else {
                            nocturnePicked = picked.argb
                            onPickNocturneColour(picked.argb)
                        }
                    }
                    app.xreader.ui.common.ColourBarSelection.ThemeColour ->
                        Unit
                }
            },
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            "You can change all of this later in Customise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp)
        )
    }
}

/** One design card on the ask page (S38/T1): an M3 card in
 * scheme language — name + one-line description, a radio-style
 * selected ring. NO miniature of either design is drawn. */
@Composable
private fun DesignAskCard(
    name: String,
    line: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (selected) {
            Text(
                "Selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp)
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
