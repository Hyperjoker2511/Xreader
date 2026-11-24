package app.xreader.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.core.motion.toSpec
import app.xreader.ui.localreader.HIGHLIGHT_TINT_ARGB

/**
 * SelectionBar build (SPEC-SELECTIONBAR §4, as revised):
 * ONE selection bar for every reader surface — the PDF
 * Pages bodies, the PDF Reflow column and the core text
 * reader all compose THIS component; there is no second
 * selection UI. The shape is Moon+'s bar (one action row,
 * text buttons, no selected-text header, no Close button)
 * in a Material 3 surface, with the colour row the owner
 * picked from the PDF mockup riding on top of the row
 * inside the same card.
 *
 * The bar is STATELESS and never sees the selected text:
 * its size is a pure function of [SelectionBarState] +
 * the width fit (§4.4) — never of the selection — so a
 * drag settle cannot change its size under its seat (the
 * MR-4 conviction: the old bar's header WAS the selected
 * text, and it grew at every settle in the same
 * recomposition that seated it with the previous width).
 * Hosts own the selection, the seat and the dismissal;
 * the bar only reports taps.
 */

/** The actions the bar can ask its host to run. Hosts map
 * the ones they support; an action a host cannot run in
 * the current episode is hidden (see [SelectionBar]'s
 * hiddenActions), never wired to a no-op. */
enum class SelectionBarAction {
    COPY, HIGHLIGHT, NOTE, DICTIONARY,
    TRANSLATE, ASK_AI, SHARE,                 // More, owner's order
    REPLACE, SAVE_WORD, SELECT_ALL, BOOKMARK  // More, core reader only
}

/** One More-menu entry: an action + its plain-word label.
 * The host builds the list in display order (§3.3); the
 * bar renders it verbatim (minus hidden actions). */
data class SelectionBarMoreItem(
    val action: SelectionBarAction,
    val label: String
)

/** The bar's callbacks, grouped so a host passes exactly
 * the actions it supports. [onAction] receives every
 * action tap EXCEPT Highlight-with-colour-row
 * interactions, which resolve through [onApplyHighlight]
 * (§7). [onDismiss] is the host's "clear the selection" —
 * the bar never dismisses itself. */
class SelectionBarCallbacks(
    val onAction: (SelectionBarAction) -> Unit,
    /** Apply (or re-apply) a highlight in [tintArgb] with
     * [style]. Fired when a colour circle is tapped (§7.2)
     * and when Highlight is tapped with the colour row
     * already open (§7.1). */
    val onApplyHighlight: (tintArgb: Int, style: HighlightStyle) -> Unit,
    /** Existing-highlight state only: the trash slot. */
    val onRemoveHighlight: () -> Unit = {},
    val onDismiss: () -> Unit
)

/** Highlight markup style. Only HIGHLIGHT is buildable on
 * today's store/render (§11 OQ-2); the enum carries the
 * one value so the API does not change when styles land. */
enum class HighlightStyle { HIGHLIGHT }

/** The bar's complete state, owned by the HOST (hoisted —
 * the bar is stateless; §5 depends on this). */
data class SelectionBarState(
    /** True when the selection IS a stored highlight
     * (Moon+'s pen-slot rule): the leading slot becomes a
     * trash icon. */
    val existingHighlight: Boolean = false,
    /** The colour row is open (inside the same surface). */
    val colourRowOpen: Boolean = false,
    /** The tint the colour row shows as active: the
     * existing highlight's tint, else the last applied
     * tint this session, else HIGHLIGHT_TINT_ARGB. */
    val activeTintArgb: Int = HIGHLIGHT_TINT_ARGB,
    /** The More menu is open. */
    val moreOpen: Boolean = false,
    /** Host capability: false where the host's highlight
     * model carries no per-highlight tint (the core
     * reader, §8.1). When false, Highlight routes straight
     * to callbacks.onAction(HIGHLIGHT) and the colour row
     * can never open. PDF hosts leave it true. */
    val colourRowEnabled: Boolean = true
)

/**
 * Code 120 fix pass (recheck L5): the tint a NEW highlight
 * takes when the user has not picked a colour — the default
 * the bar's state, its first colour-row circle and the
 * store's note-draft constructions resolve through. Under
 * Nocturne it is [HIGHLIGHT_TINT_ARGB] exactly as shipped.
 * Under Material 3 it is the active design's selection tint
 * ([app.xreader.ui.theme.LocalSelectionTint] — the scheme
 * primary under M3) at the same 0x66 alpha, as E1 rules for
 * the live selection: the Nocturne violet is never the
 * default inside the M3 design. A colour the user picked is
 * data and never passes through here.
 */
@Composable
fun defaultHighlightTintArgb(): Int =
    if (app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    ) {
        app.xreader.ui.theme.LocalSelectionTint.current
            .copy(alpha = 0.4f).toArgb()
    } else {
        HIGHLIGHT_TINT_ARGB
    }

/** The PDF hosts' More list (§3.3): the owner's surviving
 * order — Translate · Ask AI · Share. All three are
 * fulfilled for every PDF seat (§6.4), so the list is a
 * constant of the component, shared by the Pages bodies
 * and Reflow. */
val PdfSelectionMoreItems: List<SelectionBarMoreItem> = listOf(
    SelectionBarMoreItem(SelectionBarAction.TRANSLATE, "Translate"),
    SelectionBarMoreItem(SelectionBarAction.ASK_AI, "Ask AI"),
    SelectionBarMoreItem(SelectionBarAction.SHARE, "Share")
)

/** The colour row's circles, in order (§7.2): the live
 * violet tint, amber (the search-mark hue), then Moon+'s
 * sage / orange / pink slot hues. The first entry is the
 * default tint: under Material 3 the bar renders
 * [defaultHighlightTintArgb] in its place (recheck L5). */
private val SELECTION_BAR_TINTS: List<Int> = listOf(
    HIGHLIGHT_TINT_ARGB,
    0xFFFFB020.toInt(),
    0xFF99AA77.toInt(),
    0xFFFF9200.toInt(),
    0xFFCC99C0.toInt()
)

@Composable
fun SelectionBar(
    state: SelectionBarState,
    onStateChange: (SelectionBarState) -> Unit,
    moreItems: List<SelectionBarMoreItem>,
    callbacks: SelectionBarCallbacks,
    /** Actions this host/episode cannot run. A hidden ROW
     * action is not rendered; a hidden MORE action is
     * filtered from [moreItems] at render. Hidden wins
     * over every other rule, including §4.4's Dictionary
     * absorption (an absorbed Dictionary that is also
     * hidden stays hidden). The host's list is never
     * mutated. Default: nothing hidden — the PDF hosts
     * pass the default. */
    hiddenActions: Set<SelectionBarAction> = emptySet(),
    modifier: Modifier = Modifier
) {
    val designState = app.xreader.ui.theme.LocalDesignState.current
    val isM3Bar = designState.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    // Code 120 fix pass (recheck L5): the default tint under
    // Material 3. A live-selection episode whose hoisted state
    // still carries the untouched Nocturne default is
    // normalised — in the effective state below (the active
    // ring and the bar's own apply calls) and, through
    // onStateChange, in the host's state itself, so a host
    // arm that applies with state.activeTintArgb stores the
    // design's colour too. An existing highlight's stored
    // tint is user data and is never rewritten; neither is a
    // tint the user picked from the row (only the untouched
    // default matches). Under Nocturne none of this fires.
    val m3DefaultTintArgb = defaultHighlightTintArgb()
    LaunchedEffect(
        isM3Bar, m3DefaultTintArgb,
        state.activeTintArgb, state.existingHighlight
    ) {
        if (isM3Bar && !state.existingHighlight &&
            state.activeTintArgb == HIGHLIGHT_TINT_ARGB &&
            m3DefaultTintArgb != HIGHLIGHT_TINT_ARGB
        ) {
            onStateChange(
                state.copy(activeTintArgb = m3DefaultTintArgb)
            )
        }
    }
    // Code 120 (design §2.11 Reader): the selection colour
    // row's own knob — under Material 3 with the knob off, the
    // row can never open: the host's capability is ANDed with
    // the knob here, at the one component every host renders,
    // so no host needs to know. The bar's anatomy is otherwise
    // exactly as shipped (S23 — the design's reference surface).
    val knobbedState = if (isM3Bar && !designState.selectionColourRow) {
        state.copy(colourRowEnabled = false, colourRowOpen = false)
    } else {
        state
    }
    val state = if (isM3Bar && !knobbedState.existingHighlight &&
        knobbedState.activeTintArgb == HIGHLIGHT_TINT_ARGB
    ) {
        knobbedState.copy(activeTintArgb = m3DefaultTintArgb)
    } else {
        knobbedState
    }
    // The colour row's first circle IS the default tint:
    // under Material 3 it shows (and applies) the
    // scheme-derived value; the remaining circles are the
    // user's explicit colour choices in both designs.
    val barTints = if (isM3Bar) {
        SELECTION_BAR_TINTS.map { tint ->
            if (tint == HIGHLIGHT_TINT_ARGB) {
                m3DefaultTintArgb
            } else {
                tint
            }
        }
    } else {
        SELECTION_BAR_TINTS
    }
    Surface(
        modifier = modifier.widthIn(max = 344.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        BoxWithConstraints {
            // §4.4 (as revised): the fit procedure, keyed on
            // the width W the bar actually sees (≤ 344dp by
            // the cap above, whatever the host's cap is).
            // Label size NEVER changes; overflow moves
            // actions into More, in this order.
            val width = maxWidth
            // 1. Padding: 12dp iff W ≥ 348dp, else 8dp.
            val buttonPadding = if (width >= 348.dp) 12.dp else 8.dp
            // 2. Dictionary absorption: iff W < 330dp, OR
            // the trash slot is present and W < 380dp.
            val absorbDictionary = width < 330.dp ||
                (state.existingHighlight && width < 380.dp)
            // 3. Note absorption (floor guard): iff W < 296dp.
            val absorbNote = width < 296.dp
            val absorbed = buildList {
                if (absorbDictionary &&
                    SelectionBarAction.DICTIONARY !in hiddenActions
                ) {
                    add(
                        SelectionBarMoreItem(
                            SelectionBarAction.DICTIONARY, "Dictionary"
                        )
                    )
                }
                if (absorbNote &&
                    SelectionBarAction.NOTE !in hiddenActions
                ) {
                    add(
                        SelectionBarMoreItem(
                            SelectionBarAction.NOTE, "Note"
                        )
                    )
                }
            }
            val renderedMore = absorbed + moreItems.filter {
                it.action !in hiddenActions
            }
            Column {
                if (state.colourRowOpen && state.colourRowEnabled) {
                    Row(
                        modifier = Modifier
                            .height(56.dp)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        barTints.forEach { tint ->
                            SelectionTintCircle(
                                tintArgb = tint,
                                active = tint == state.activeTintArgb,
                                onClick = {
                                    onStateChange(
                                        state.copy(activeTintArgb = tint)
                                    )
                                    callbacks.onApplyHighlight(
                                        tint, HighlightStyle.HIGHLIGHT
                                    )
                                }
                            )
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(
                    modifier = Modifier.height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.existingHighlight) {
                        IconButton(
                            onClick = { callbacks.onRemoveHighlight() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove highlight",
                                tint = MaterialTheme
                                    .colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (SelectionBarAction.COPY !in hiddenActions) {
                        SelectionBarTextButton("Copy", buttonPadding) {
                            callbacks.onAction(SelectionBarAction.COPY)
                        }
                    }
                    if (SelectionBarAction.HIGHLIGHT !in hiddenActions) {
                        SelectionBarTextButton("Highlight", buttonPadding) {
                            when {
                                !state.colourRowEnabled ->
                                    callbacks.onAction(
                                        SelectionBarAction.HIGHLIGHT
                                    )
                                !state.colourRowOpen ->
                                    onStateChange(
                                        state.copy(colourRowOpen = true)
                                    )
                                else -> callbacks.onApplyHighlight(
                                    state.activeTintArgb,
                                    HighlightStyle.HIGHLIGHT
                                )
                            }
                        }
                    }
                    if (SelectionBarAction.NOTE !in hiddenActions &&
                        !absorbNote
                    ) {
                        SelectionBarTextButton("Note", buttonPadding) {
                            callbacks.onAction(SelectionBarAction.NOTE)
                        }
                    }
                    if (SelectionBarAction.DICTIONARY !in hiddenActions &&
                        !absorbDictionary
                    ) {
                        SelectionBarTextButton("Dictionary", buttonPadding) {
                            callbacks.onAction(
                                SelectionBarAction.DICTIONARY
                            )
                        }
                    }
                    if (renderedMore.isNotEmpty()) {
                        Box {
                            TextButton(
                                onClick = {
                                    onStateChange(
                                        state.copy(moreOpen = true)
                                    )
                                },
                                contentPadding = PaddingValues(
                                    horizontal = buttonPadding
                                )
                            ) {
                                Text(
                                    "More",
                                    style = MaterialTheme
                                        .typography.labelLarge,
                                    color = MaterialTheme
                                        .colorScheme.primary
                                )
                            }
                            DropdownMenu(
                                expanded = state.moreOpen,
                                onDismissRequest = {
                                    onStateChange(
                                        state.copy(moreOpen = false)
                                    )
                                }
                            ) {
                                renderedMore.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(item.label) },
                                        onClick = {
                                            onStateChange(
                                                state.copy(moreOpen = false)
                                            )
                                            callbacks.onAction(item.action)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One action-row button: M3 TextButton, labelLarge,
 * text-only, labels in onSurface (§4.3). */
@Composable
private fun SelectionBarTextButton(
    label: String,
    horizontalPadding: Dp,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = horizontalPadding)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** One colour-row circle: the tint itself, ringed in
 * onSurface when it is the active tint (the ring reads on
 * every tint, §4.3). */
@Composable
private fun SelectionTintCircle(
    tintArgb: Int,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(tintArgb))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (active) {
            Box(
                Modifier
                    .size(28.dp)
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.onSurface,
                        CircleShape
                    )
            )
        }
    }
}

/** The §9.3 seat motion values for one SelectionBar seat:
 * the entry fade + scale and the post-action fade, as ONE
 * mechanism at every host. [alpha] runs 0 → 1 on entry
 * (selection entry only — the latch keeps re-seats from
 * re-running it) and 1 → 0 over 150ms while [dismissing];
 * [scale] springs 0.96 → 1 on the Dialog profile. With
 * [motionEnabled] false both collapse to instant (§9.4). */
class SelectionBarSeatMotion(val alpha: Float, val scale: Float)

@Composable
fun rememberSelectionBarSeatMotion(
    visible: Boolean,
    dismissing: Boolean,
    motionEnabled: Boolean
): SelectionBarSeatMotion {
    // Entry latch: the bar starts transparent and fades in
    // on the frame after it appears.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { shown = visible }
    val alpha by animateFloatAsState(
        targetValue = if (shown && !dismissing) 1f else 0f,
        animationSpec = if (motionEnabled) tween(150) else snap(),
        label = "selectionBarAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (shown && !dismissing) 1f else 0.96f,
        animationSpec = if (motionEnabled) {
            SpringMotionEngine.Dialog.toSpec()
        } else {
            snap()
        },
        label = "selectionBarScale"
    )
    return SelectionBarSeatMotion(alpha, scale)
}
