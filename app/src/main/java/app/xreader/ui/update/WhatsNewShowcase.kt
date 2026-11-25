package app.xreader.ui.update

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.xreader.data.prefs.DesignState
import kotlinx.coroutines.launch

/**
 * Code 121 (R39): this file carries TWO showcases, kept apart
 * by design:
 *
 * 1. [WhatsNewRevealEditorial] — the reveal for a
 *    DESIGN-CARRYING update (code 123's design, renamed
 *    Material 3 in code 124). It wears the NEW design
 *    itself — composed inside
 *    [app.xreader.ui.theme.EditorialThemeRoot] regardless of
 *    the active design — it is TRULY MODAL (a [Dialog];
 *    touches cannot reach the shell beneath it), and its
 *    seen-record is [RevealPrefs], written only by its own
 *    terminal actions (Try / Keep mine / Skip / finishing
 *    the last card) and read only by its gate in
 *    MainActivity. (The code-121 M3 reveal that stood here
 *    was removed with its design in code 124.)
 *
 * 2. [WhatsNewShowcase] — the release showcase. It wears the
 *    ACTIVE design, as before, and keeps its OWN seen-store
 *    ([ShowcasePrefs]; code 125 split it off the GamePrefs
 *    store it used to share with the Me note). Code 128: when
 *    the reveal is still unseen, the showcase OPENS with the
 *    design card (the reveal's first card, as itself, with
 *    Try / Keep) and its finish settles the reveal's record
 *    too — MainActivity owns both writes; the reveal above
 *    remains as the fallback when this gate is closed.
 */

// ── The reveal (R39) ─────────────────────────────────────────────

/**
 * Code 123 (R39, re-pointed): the reveal for THIS design-
 * carrying update — the Editorial design. Same mechanics as
 * [WhatsNewReveal]: truly modal (a [Dialog]), its seen-record
 * is [RevealPrefs] written only by its terminal actions, Skip
 * on every card, and it never switches anyone automatically.
 *
 * It presents the design AS ITSELF — composed inside
 * [app.xreader.ui.theme.EditorialThemeRoot] with the design
 * forced to Editorial and the mode forced to LIGHT for this
 * subtree only: the light paper face is the design as the
 * owner approved it, page by page, and the reveal is where
 * existing users meet it. (The stored design and mode are
 * untouched unless Try is tapped; a user who tries Editorial
 * gets its dark form too, from their own mode setting.)
 *
 * [onTryDesign] must switch the design to Editorial
 * (DesignPrefs) and then finish; [onFinish] records the
 * reveal seen and dismisses.
 */
@Composable
fun WhatsNewRevealEditorial(
    designState: DesignState,
    onTryDesign: () -> Unit,
    onFinish: () -> Unit
) {
    val previewState = remember(designState) {
        designState.copy(
            design = app.xreader.data.prefs.AppDesign.EDITORIAL,
            themeMode = app.xreader.data.prefs.M3ThemeMode.LIGHT
        )
    }
    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        app.xreader.ui.theme.EditorialThemeRoot(
            design = previewState,
            reduceMotion = false
        ) {
            EditorialRevealPager(
                onTryDesign = onTryDesign,
                onFinish = onFinish
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditorialRevealPager(
    onTryDesign: () -> Unit,
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val lastPage = 2
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
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (page) {
                        0 -> EditorialRevealFeature(
                            title = "Paper & sage",
                            body = "A new design for Xreader — warm " +
                                "paper, a sage accent and a serif " +
                                "voice, drawn around your books. " +
                                "You are looking at it now.",
                            demo = { EditorialLibraryDemo() }
                        )
                        1 -> EditorialRevealFeature(
                            title = "Your books, front and centre",
                            body = "In-progress books lead the " +
                                "Library with your place on every " +
                                "cover, and the book page carries " +
                                "the book's own Contents.",
                            demo = { EditorialBookDemo() }
                        )
                        else -> Column(
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
                                    .clip(CircleShape)
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
                            Button(
                                onClick = onTryDesign,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Try Material 3")
                            }
                            FilledTonalButton(
                                onClick = onFinish,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Keep mine")
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
                    TextButton(onClick = onFinish) { Text("Done") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditorialRevealFeature(
    title: String,
    body: String,
    demo: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            demo()
        }
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmallEmphasized
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The reveal's Library miniature — the copied page's own
 * atoms (search pill, serif headline, a Continue card) in
 * miniature. A demo arrangement only; it navigates nothing. */
@Composable
private fun EditorialLibraryDemo() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Filled.Menu, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Search your library",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "Library",
            style = MaterialTheme.typography.headlineMedium
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 56.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer
                        )
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        "Continue reading",
                        style = MaterialTheme.typography.titleSmall
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { 0.63f },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme
                            .secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "63% · Chapter 214",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow, null,
                        tint = MaterialTheme.colorScheme
                            .onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** The reveal's book-page miniature — the design's progress
 * ring and its two tiles, in miniature. */
@Composable
private fun EditorialBookDemo() {
    Row(
        Modifier.padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(92.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { 0.63f },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme
                    .secondaryContainer,
                strokeWidth = 9.dp,
                modifier = Modifier.size(92.dp)
            )
            Text(
                "63%",
                style = MaterialTheme.typography.titleLarge
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.size(width = 120.dp, height = 64.dp)
            ) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Catch me up",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme
                            .onPrimaryContainer
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.size(width = 120.dp, height = 64.dp)
            ) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Lore",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme
                            .onTertiaryContainer
                    )
                }
            }
        }
    }
}

// ── The release showcase ────────────────────────────────────────

/**
 * The release showcase — ONE set of cards for the update,
 * wearing the ACTIVE design, as it always has. Code 125
 * (F4): finishing or skipping records the version in the
 * showcase's OWN store ([ShowcasePrefs]) — never the Me
 * note's GamePrefs record any more — and Back counts as a
 * dismissal too (the BackHandler below routes it through
 * the same [onFinish]).
 *
 * Code 128 (the v1.7.0 release set, RELEASE-DRAFT-2026-11-25):
 * the code-125 fix cards are replaced by the release set.
 * When [showDesignCard] is set, card 1 is the Material 3
 * design presented AS ITSELF — composed inside
 * [app.xreader.ui.theme.EditorialThemeRoot] with the design
 * forced to Editorial and the mode forced to LIGHT, the
 * reveal's own atoms — with Try / Keep on the card: Try is
 * terminal ([onTryDesign]: MainActivity switches the design
 * and settles BOTH seen-records), Keep advances into the
 * feature cards, and any later finish settles both records
 * too (MainActivity owns the records; this composable only
 * reports the terminal actions). Existing users are never
 * auto-switched. The standalone reveal
 * ([WhatsNewRevealEditorial]) remains as the fallback for
 * an install whose showcase gate is closed but whose reveal
 * is still unseen. Cards 2+ present the release's features
 * — TXT books, the opening, Ask AI's page, the book page,
 * the PDF fixes, History — each with its own small
 * animation or live demo of real atoms, never a placeholder.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WhatsNewShowcase(
    designState: DesignState,
    showDesignCard: Boolean,
    onTryDesign: () -> Unit,
    onFinish: () -> Unit
) {
    // Code 125 (F4): Back dismisses the showcase AND writes
    // the seen record through onFinish. Before this, Back
    // left the showcase (the app exited) without any write,
    // so the showcase returned on the next launch — the
    // repeat the owner saw.
    androidx.activity.compose.BackHandler { onFinish() }
    // Code 127 (audit G1): TRULY MODAL, like the design
    // reveal — the showcase used to be composed inline in
    // the shell, so app chrome and gestures leaked around
    // it. Same Dialog contract as WhatsNewRevealEditorial:
    // full-bleed, Back dismisses (writing seen), no
    // click-outside dismiss. The pager inside wears the
    // active face (see WhatsNewShowcasePager).
    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        WhatsNewShowcasePager(
            designState = designState,
            showDesignCard = showDesignCard,
            onTryDesign = onTryDesign,
            onFinish = onFinish
        )
    }
}

@Composable
private fun WhatsNewShowcasePager(
    designState: DesignState,
    showDesignCard: Boolean,
    onTryDesign: () -> Unit,
    onFinish: () -> Unit
) {
    // Code 127 (audit G1): the Nocturne chrome — glass
    // feature cards and the kit's pills — under Nocturne;
    // the Material 3 face keeps its Card + Button form.
    // Code 128: the release set (draft order: TXT, the
    // opening, Ask AI, the book page, PDF, History). When
    // the design card leads, it is composed inside
    // EditorialThemeRoot as itself (below); the feature
    // cards wear the active face as before.
    val nocturne = !app.xreader.ui.theme.isEditorialDesign()
    val features = remember {
        listOf(
            ShowcaseFeature(
                title = "TXT books, fully read",
                body = "Plain-text books open as real books " +
                    "now: chapters are detected for you, " +
                    "encodings like GBK, Big5 and Russian are " +
                    "read correctly — with an encoding door on " +
                    "the book page — and chapters can be " +
                    "re-detected any time.",
                demo = ShowcaseDemo.TXT
            ),
            ShowcaseFeature(
                title = "A proper opening",
                body = "Xreader opens with its mark now: the " +
                    "logo animates in, in your design's " +
                    "colours, and hands over to your Library " +
                    "the moment it is ready.",
                demo = ShowcaseDemo.SPLASH
            ),
            ShowcaseFeature(
                title = "Ask AI has its own page",
                body = "Ask AI opens as its own full page — " +
                    "Summarize, Catch me up, Translate, " +
                    "Illustrate and Lore chat — with Back " +
                    "taking you exactly where you were.",
                demo = ShowcaseDemo.AI_PAGE
            ),
            ShowcaseFeature(
                title = "The book page, tidied",
                body = "Chapters show their release dates, a " +
                    "pull down from the top refreshes the " +
                    "list, and Download all sits beside " +
                    "per-chapter downloads.",
                demo = ShowcaseDemo.BOOK_PAGE
            ),
            ShowcaseFeature(
                title = "PDF, fixed",
                body = "A highlight is a see-through wash " +
                    "now — the words stay readable through " +
                    "the colour — and the page strip shows " +
                    "the real page on every thumbnail, not a " +
                    "dark card with a number.",
                demo = ShowcaseDemo.PDF_FIXES
            ),
            ShowcaseFeature(
                title = "History, fixed",
                body = "No more red behind the cards, the " +
                    "reading pulse shows its numbers again, " +
                    "every card carries its progress and its " +
                    "actions whole, and Clear history sits in " +
                    "the open.",
                demo = ShowcaseDemo.HISTORY
            )
        )
    }
    val pageCount = features.size + if (showDesignCard) 1 else 0
    val pagerState = rememberPagerState(pageCount = { pageCount })
    // The design card's preview state: the design forced to
    // Editorial, the mode forced to LIGHT for that subtree
    // only — the reveal's own construction.
    val designPreview = remember(designState) {
        designState.copy(
            design = app.xreader.data.prefs.AppDesign.EDITORIAL,
            themeMode = app.xreader.data.prefs.M3ThemeMode.LIGHT
        )
    }
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
                if (showDesignCard && page == 0) {
                    // Card 1: the design AS ITSELF — the whole
                    // card composed inside the Editorial theme
                    // root (forced LIGHT preview), exactly as
                    // the reveal presents it.
                    app.xreader.ui.theme.EditorialThemeRoot(
                        design = designPreview,
                        reduceMotion = false
                    ) {
                        ShowcaseDesignCard(
                            onTryDesign = onTryDesign,
                            onKeep = {
                                scope.launch {
                                    pagerState.animateScrollToPage(1)
                                }
                            }
                        )
                    }
                } else {
                    val feature = features[
                        page - if (showDesignCard) 1 else 0
                    ]
                    if (nocturne) {
                        app.xreader.ui.common.GlassCard(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            ShowcaseFeatureContent(feature)
                        }
                    } else {
                        Card(
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(
                                containerColor =
                                    MaterialTheme.colorScheme
                                        .surfaceContainer
                            ),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            ShowcaseFeatureContent(feature)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (nocturne) {
                    app.xreader.ui.common.GhostPill(
                        text = "Skip",
                        enabled = true,
                        onClick = onFinish
                    )
                } else {
                    TextButton(onClick = onFinish) { Text("Skip") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0 until pageCount).forEach { index ->
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
                val nextLabel = if (pagerState.currentPage <
                    pageCount - 1
                ) {
                    "Next"
                } else {
                    "Done"
                }
                val onNext: () -> Unit = {
                    if (pagerState.currentPage <
                        pageCount - 1
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
                if (nocturne) {
                    app.xreader.ui.common.PrimaryPill(
                        text = nextLabel,
                        onClick = onNext
                    )
                } else {
                    Button(onClick = onNext) { Text(nextLabel) }
                }
            }
        }
    }
}

/**
 * Card 1 of the release showcase (code 128): the Material 3
 * design presented AS ITSELF. Composed by the pager inside
 * [app.xreader.ui.theme.EditorialThemeRoot] with the LIGHT
 * preview state, so every colour and shape here is the
 * design's own. The stage carries the reveal's two demo
 * atoms (the Library miniature and the book-page miniature);
 * Try is terminal (MainActivity switches the design and
 * settles both records), Keep advances to the feature
 * cards. Nobody is switched without tapping Try.
 */
@Composable
private fun ShowcaseDesignCard(
    onTryDesign: () -> Unit,
    onKeep: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surfaceContainerLow
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
                            .secondaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(18.dp),
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {
                    EditorialLibraryDemo()
                    EditorialBookDemo()
                }
            }
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "A whole new design — Material 3",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "A new design for Xreader — warm paper, a " +
                        "sage accent and a serif voice, drawn " +
                        "around your books. You are looking at " +
                        "it now. Try it, or keep the design you " +
                        "have — you can switch any time in " +
                        "Customise → Design.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme
                        .onSurfaceVariant
                )
                Button(
                    onClick = onTryDesign,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Material 3")
                }
                FilledTonalButton(
                    onClick = onKeep,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Keep mine")
                }
            }
        }
    }
}

/** One feature card's insides — the demo stage over the
 * title and body — shared by both faces' card containers
 * (code 127, audit G1). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShowcaseFeatureContent(feature: ShowcaseFeature) {
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
                ShowcaseDemo.TXT ->
                    TxtBooksDemo()
                ShowcaseDemo.SPLASH ->
                    SplashDemo()
                ShowcaseDemo.AI_PAGE ->
                    AskAiPageDemo()
                ShowcaseDemo.BOOK_PAGE ->
                    BookPageDemo()
                ShowcaseDemo.PDF_FIXES ->
                    PdfFixesDemo()
                ShowcaseDemo.HISTORY ->
                    HistoryFixedDemo()
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

private data class ShowcaseFeature(
    val title: String,
    val body: String,
    val demo: ShowcaseDemo
)

private enum class ShowcaseDemo {
    TXT, SPLASH, AI_PAGE, BOOK_PAGE, PDF_FIXES, HISTORY
}

/**
 * Demo: a TXT book read as a book (code 128) — the file's
 * name with its detected encoding, the detected chapter
 * list with a scan highlight travelling down it (the
 * detection pass, in miniature), and the re-detect and
 * encoding doors. All geometry is fixed: three 36dp rows
 * in a 108dp stage, the highlight a 36dp band whose offset
 * is the phase times the fixed travel — nothing sizes
 * itself from a wrap-content parent.
 */
@Composable
private fun TxtBooksDemo() {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "txtDemo")
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "txtDemoScan"
    )
    Column(
        Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "sword-chronicle.txt",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            Surface(
                color = scheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "Encoding · GBK",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSecondaryContainer,
                    modifier = Modifier.padding(
                        horizontal = 10.dp, vertical = 6.dp
                    )
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(108.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .offset(y = (scan * 72).dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.primaryContainer)
            )
            Column {
                listOf(
                    "Chapter 1 — The Rain Sword",
                    "Chapter 2 — A Name Spoken",
                    "Chapter 3 — The Road North"
                ).forEach { title ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography
                                .bodyMedium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(
                color = scheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "Re-detect chapters",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(
                        horizontal = 10.dp, vertical = 6.dp
                    )
                )
            }
            Surface(
                color = scheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "Encoding…",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(
                        horizontal = 10.dp, vertical = 6.dp
                    )
                )
            }
        }
    }
}

/**
 * Demo: the opening (code 128) — the branded intro in
 * miniature: the mark and the wordmark fade and settle in
 * on a loop, on the intro's own dark ground. Fixed sizes,
 * centred; nothing is measured from anything else.
 */
@Composable
private fun SplashDemo() {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "splashDemo")
    val appear by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splashDemoAppear"
    )
    Box(
        Modifier
            .width(280.dp)
            .height(190.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B0B0F)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .alpha(0.25f + 0.75f * appear)
                .scale(0.94f + 0.06f * appear)
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "X",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onPrimary
                )
            }
            Text(
                "Xreader",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
        }
    }
}

/**
 * Demo: the PDF fixes (code 128) — the read-through wash
 * over a real phrase, with the filmstrip of real pages
 * beneath it. The wash is drawn BEHIND the phrase's own
 * text with drawBehind, sized from the text's own draw
 * size. Code 127's demo sized the wash with
 * fillMaxWidth/fillMaxHeight inside a wrap-content parent,
 * so it inflated to the whole stage and crushed the words
 * beside it into one-letter-per-line wrapping (the owner's
 * F-A circle). A wash measured from the words themselves
 * cannot do that; the handles ride the wash's own ends.
 */
@Composable
private fun PdfFixesDemo() {
    val scheme = MaterialTheme.colorScheme
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
    val wash = scheme.primary.copy(alpha = 0.40f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            Modifier
                .width(260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "The sword master stepped into the rain and " +
                    "did not look back.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "He carried ",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "the rain",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.drawBehind {
                        val reach = grow.coerceAtLeast(0.06f)
                        val washWidth = size.width * reach
                        drawRoundRect(
                            color = wash,
                            topLeft =
                                androidx.compose.ui.geometry.Offset(
                                    -3.dp.toPx(),
                                    size.height * 0.10f
                                ),
                            size =
                                androidx.compose.ui.geometry.Size(
                                    washWidth + 6.dp.toPx(),
                                    size.height * 0.80f
                                ),
                            cornerRadius =
                                androidx.compose.ui.geometry
                                    .CornerRadius(
                                        3.dp.toPx(), 3.dp.toPx()
                                    )
                        )
                        drawCircle(
                            color = scheme.primary,
                            radius = 3.5.dp.toPx(),
                            center =
                                androidx.compose.ui.geometry.Offset(
                                    -3.dp.toPx(),
                                    size.height + 2.dp.toPx()
                                )
                        )
                        drawCircle(
                            color = scheme.primary,
                            radius = 3.5.dp.toPx(),
                            center =
                                androidx.compose.ui.geometry.Offset(
                                    -3.dp.toPx() + washWidth +
                                        6.dp.toPx(),
                                    size.height + 2.dp.toPx()
                                )
                        )
                    }
                )
                Text(
                    " on his shoulders.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                "Behind him the town kept its lights on, " +
                    "waiting for a name it would never hear " +
                    "again.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
        }
        FilmstripDemo()
    }
}

/**
 * Demo: the scrubber filmstrip — five real thumbnails, each
 * a tiny page of real words with its page number, the
 * current one outlined in the accent. No dark number-only
 * cards.
 */
@Composable
private fun FilmstripDemo() {
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            212 to "The night was cold.",
            213 to "He lit the last lamp.",
            214 to "Rain on the roof.",
            215 to "A knock, then silence.",
            216 to "Morning came grey."
        ).forEachIndexed { index, (number, line) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier
                        .size(width = 46.dp, height = 64.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.surface)
                        .then(
                            if (index == 2) {
                                Modifier.border(
                                    2.dp, scheme.primary,
                                    RoundedCornerShape(4.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(5.dp)
                ) {
                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            "Ch. $number",
                            fontSize = 7.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            line,
                            fontSize = 6.sp,
                            lineHeight = 8.sp,
                            color = scheme.onSurfaceVariant,
                            maxLines = 3
                        )
                    }
                }
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Demo: Ask AI as a page — the real page furniture in
 * miniature: back arrow, title, the five feature tiles by
 * their real names, and the ask field with its send.
 */
@Composable
private fun AskAiPageDemo() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "Ask AI",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "Summarize", "Catch me up", "Translate",
                "Illustrate", "Lore chat"
            ).forEach { label ->
                Surface(
                    color = scheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSecondaryContainer,
                        modifier = Modifier.padding(
                            horizontal = 10.dp, vertical = 6.dp
                        )
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = scheme.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "Ask about the story…",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = 12.dp, vertical = 9.dp
                    )
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(scheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Demo: the History card fixed — the pulse with its real
 * numbers above the labels, and one card with its progress
 * bar, its Continue pill and its Remove, whole.
 */
@Composable
private fun HistoryFixedDemo() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            listOf(
                "42 min" to "read today",
                "3" to "books today",
                "12" to "chapters",
                "180" to "EXP today"
            ).forEach { (value, label) ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        value,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        Surface(
            color = scheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Nano Machine",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Chapter 214 · 0% through the book",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = scheme.primary,
                    trackColor = scheme.surfaceContainerHighest
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = scheme.primary,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            "Continue · Ch. 214",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onPrimary,
                            modifier = Modifier.padding(
                                horizontal = 12.dp, vertical = 7.dp
                            )
                        )
                    }
                    Text(
                        "Remove",
                        fontSize = 13.sp,
                        color = scheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Demo: the book page tidied — Contents rows with their
 * real release dates under the titles, and the actions
 * whole (code 128: in a wrapping flow — see below).
 */
@Composable
private fun BookPageDemo() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        listOf(
            "Chapter 214" to "3 days ago",
            "Chapter 215" to "Yesterday",
            "Chapter 216" to "12 Nov 2026"
        ).forEach { (title, date) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title.takeLast(3),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurfaceVariant
                    )
                }
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        date,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
        // Code 128 (audit X3): the three action chips ride a
        // FlowRow, not a fixed Row — as a Row their combined
        // non-shrinking widths (~267dp) overran this demo's
        // 252dp content box and the last chip ran past the
        // card's rounded clip. In the flow each chip stays
        // whole; the third wraps to its own line.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Refresh", "Download all", "Save as EPUB")
                .forEach { label ->
                    Surface(
                        color = scheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSecondaryContainer,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(
                                horizontal = 10.dp, vertical = 7.dp
                            )
                        )
                    }
                }
        }
    }
}
