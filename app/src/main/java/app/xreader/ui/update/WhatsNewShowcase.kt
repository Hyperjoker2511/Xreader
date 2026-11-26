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
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
    // Patch B (B-6, B4c): a Dialog is its own Android
    // WINDOW — its frame is sized by the window manager
    // from the window's own display metrics, while its
    // content composes under LocalDensity. The app's ONE
    // size authority is the theme root's scaled density
    // (XreaderTheme: Density(base, fontScale = appSize)).
    // Capture it HERE, in the activity composition, and
    // pin it inside the dialog, so this surface's content
    // is measured by the app's density and never by
    // whatever the dialog window's own view would seed —
    // on the 2 GB phone (whose window/configuration
    // metrics already diverge from the app's, the known
    // app-wide layout failure) the unpinned dialog was
    // the one update surface that could split-scale:
    // frame from the window, content from elsewhere.
    val hostDensity = LocalDensity.current
    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        CompositionLocalProvider(LocalDensity provides hostDensity) {
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
 * is still unseen. Cards 2+ present the app's features the
 * way a first-time reader meets them — every format in one
 * reader, TXT books, Ask AI's page, making the app yours,
 * the book page, PDF highlights — each with its own small
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
    // Patch B (B-6, B4c): the same density pin as the
    // reveal above — the app's theme-scaled LocalDensity,
    // captured in the activity composition, is re-provided
    // inside this Dialog window so the showcase scales as
    // one piece with the rest of the app (see the note in
    // WhatsNewRevealEditorial).
    val hostDensity = LocalDensity.current
    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        CompositionLocalProvider(LocalDensity provides hostDensity) {
            WhatsNewShowcasePager(
                designState = designState,
                showDesignCard = showDesignCard,
                onTryDesign = onTryDesign,
                onFinish = onFinish
            )
        }
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
    // Code 128 set, copy rewritten in code 129 for
    // first-time readers: the cards present the app's
    // features as features, in this order — every format
    // in one reader, TXT books, Ask AI's page, making the
    // app yours, the book page, PDF highlights. When the
    // design card leads, it is composed inside
    // EditorialThemeRoot as itself (below); the feature
    // cards wear the active face as before.
    val nocturne = !app.xreader.ui.theme.isEditorialDesign()
    val features = remember {
        listOf(
            ShowcaseFeature(
                title = "Every format, one reader",
                body = "Novels, EPUB, PDF and TXT books " +
                    "and manga all live in one Library and " +
                    "open in one reader — every book you " +
                    "have, in one place, read the same way.",
                demo = ShowcaseDemo.FORMATS
            ),
            ShowcaseFeature(
                title = "TXT books read properly",
                body = "Open a plain-text book and its " +
                    "chapters are found for you. Encodings " +
                    "like GBK, Big5, Russian and more are " +
                    "picked up on their own, there is an " +
                    "encoding door on the book page when a " +
                    "file needs a nudge, and you can switch " +
                    "between Simplified and Traditional " +
                    "Chinese.",
                demo = ShowcaseDemo.TXT
            ),
            ShowcaseFeature(
                title = "Ask AI has its own page",
                body = "Ask AI opens as its own full page, " +
                    "with room to read and think: Summarize " +
                    "a chapter, Catch me up on the story, " +
                    "Translate a passage, Illustrate a " +
                    "moment, or chat about the lore — and " +
                    "Back takes you straight back to your " +
                    "book.",
                demo = ShowcaseDemo.AI_PAGE
            ),
            ShowcaseFeature(
                title = "Make it yours",
                body = "Two designs to choose from, " +
                    "colours you pick yourself, and text " +
                    "sized the way you like to read — every " +
                    "choice lives in one Customise space, " +
                    "so you set it up once and just read.",
                demo = ShowcaseDemo.MAKE_YOURS
            ),
            ShowcaseFeature(
                title = "The book page",
                body = "Everything about your book on one " +
                    "page: chapters with their release " +
                    "dates, Download all for the whole book " +
                    "or a download for a single chapter, " +
                    "and a gentle pull down from the top " +
                    "to refresh the list.",
                demo = ShowcaseDemo.BOOK_PAGE
            ),
            ShowcaseFeature(
                title = "PDF highlights you can read through",
                body = "Highlight a line in a PDF and the " +
                    "colour sits as a see-through wash — " +
                    "the words stay clear underneath. And " +
                    "in the page strip, every preview shows " +
                    "your real page, so you can see where " +
                    "you are going.",
                demo = ShowcaseDemo.PDF_FIXES
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
                ShowcaseDemo.FORMATS ->
                    FormatsDemo()
                ShowcaseDemo.AI_PAGE ->
                    AskAiPageDemo()
                ShowcaseDemo.BOOK_PAGE ->
                    BookPageDemo()
                ShowcaseDemo.PDF_FIXES ->
                    PdfFixesDemo()
                ShowcaseDemo.MAKE_YOURS ->
                    MakeItYoursDemo()
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
    TXT, FORMATS, AI_PAGE, BOOK_PAGE, PDF_FIXES, MAKE_YOURS
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
        // Code 129: the Simplified ⇄ Traditional switch the
        // caption names, as its own pill on its own row —
        // adding it to the row above would have pushed that
        // row past the demo's 252dp content box.
        Surface(
            color = scheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                "Simplified ⇄ Traditional",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSecondaryContainer,
                maxLines = 1,
                modifier = Modifier.padding(
                    horizontal = 10.dp, vertical = 6.dp
                )
            )
        }
    }
}

/**
 * Demo: every format, one reader (code 129) — a Library
 * shelf miniature: four cover-shaped tiles in a fixed 2×2
 * grid, one per format (EPUB, PDF, TXT, Manga), each cover
 * in a container colour of the active scheme with its
 * format named on the cover and a short kind line under
 * it, and one pill under the grid naming the one reader
 * they all open in. All geometry is fixed: the card is
 * 280dp wide with 14dp padding (252dp of content), each
 * grid row is two weight-1 tiles, every cover is 52dp
 * tall — nothing sizes itself from a wrap-content parent,
 * so the grid lays out the same at 411dp width as the
 * other demos do.
 */
@Composable
private fun FormatsDemo() {
    val scheme = MaterialTheme.colorScheme
    val formats = listOf(
        Triple("EPUB", "Novels & books", scheme.primaryContainer to scheme.onPrimaryContainer),
        Triple("PDF", "Documents", scheme.tertiaryContainer to scheme.onTertiaryContainer),
        Triple("TXT", "Plain-text books", scheme.secondaryContainer to scheme.onSecondaryContainer),
        Triple("Manga", "Comics", scheme.surfaceContainerHigh to scheme.onSurface)
    )
    Column(
        Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Library",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        formats.chunked(2).forEach { rowFormats ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowFormats.forEach { (format, kind, colours) ->
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colours.first),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                format,
                                style = MaterialTheme.typography
                                    .titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colours.second,
                                maxLines = 1
                            )
                        }
                        Text(
                            kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        Surface(
            color = scheme.primary,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.MenuBook, null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    "All open in one reader",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onPrimary,
                    maxLines = 1
                )
            }
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
 * Demo: make it yours (code 129) — choice in miniature:
 * the two designs side by side as fixed miniatures (the
 * design card's own atoms are full-width page furniture,
 * too wide to reuse inside a 121dp half, so each design
 * is drawn here at miniature scale in its own colours —
 * paper with a sage accent, near-black with violet), a
 * row of five colour swatches with the picked one
 * carrying a check, and a text-size slider miniature
 * between a small A and a large A. All geometry is
 * fixed: the card is 280dp wide with 14dp padding (252dp
 * of content), the two miniatures split it by weight at
 * a fixed 72dp tall, swatches are 28dp circles and the
 * slider track is 4dp — nothing sizes itself from a
 * wrap-content parent.
 */
@Composable
private fun MakeItYoursDemo() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Material 3 miniature: paper ground, sage accent.
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF5F0E6))
                        .border(
                            2.dp, scheme.primary,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(8.dp)
                ) {
                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            Modifier
                                .width(44.dp)
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF3E4A3D))
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFDCE5D8))
                        )
                        Box(
                            Modifier
                                .width(30.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFF6B8563))
                        )
                    }
                    Box(
                        Modifier
                            .size(18.dp)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(scheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Check, null,
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Text(
                    "Material 3",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            // Nocturne miniature: near-black ground, violet.
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0B0B0F))
                        .padding(8.dp)
                ) {
                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            Modifier
                                .width(44.dp)
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1D1D26))
                        )
                        Box(
                            Modifier
                                .width(30.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFF8B5CF6))
                        )
                    }
                }
                Text(
                    "Nocturne",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Color(0xFF8B5CF6), Color(0xFF4C7DD9),
                Color(0xFF6B8563), Color(0xFFD97A4C),
                Color(0xFFD94C7A)
            ).forEachIndexed { index, swatch ->
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(swatch),
                    contentAlignment = Alignment.Center
                ) {
                    if (index == 0) {
                        Icon(
                            Icons.Filled.Check, null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "A",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(14.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.surfaceContainerHigh)
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(0.6f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.primary)
                )
                // The thumb rides the filled portion's end:
                // a 60%-width overlay aligned to the start,
                // its end centred on the thumb.
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(0.6f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        Modifier
                            .offset(x = 7.dp)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(scheme.primary)
                    )
                }
            }
            Text(
                "A",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
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
        ).forEachIndexed { index, (title, date) ->
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
                if (index == 1) {
                    // Code 129: the per-chapter download the
                    // caption names, on one row — a fixed
                    // 20dp icon at the row's end; the row's
                    // other geometry is unchanged.
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
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
