package app.xreader.ui.reader

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.ai.LanguageDetect
import app.xreader.data.ai.SourceLanguage
import app.xreader.data.db.Highlight
import app.xreader.data.prefs.AiFeature
import app.xreader.di.AppContainer
import app.xreader.ui.localreader.HIGHLIGHT_TINT_ARGB
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Code 117 (SPEC-OWNSELECTION §7): the core reader's selection
 * bar, driven by the reader's OWN selection model
 * ([ReaderTextSelection], held in DocumentReader) instead of
 * the framework's selection manager.
 *
 * What died with the manager: `ReaderTextToolbar` (the whole
 * TextToolbar bridge), the `LocalTextToolbar` provider, and
 * the clipboard-readback fetch — the selected text is now
 * ASSEMBLED from the model by the screen's §7.3 helper and
 * handed in as [ReaderSelectionMenuHost]'s `selectionText`;
 * Copy writes it to the clipboard and nothing is ever read
 * back. Highlight / Note / Bookmark fulfil against the model's
 * TRUE range through the range callback (§8) — there is no
 * paragraph anchor any more, so `hiddenActions` is always
 * empty and those arms can never vanish mid-selection (S1's
 * mechanism is deleted, not patched).
 *
 * The bar is the shared [SelectionBar] — the same component
 * the PDF surfaces host. The Popup that carries it is NOT
 * focusable and dismisses nothing itself: dismissal is
 * exclusively the model's (§3.4's trigger list); the Popup is
 * a pure drawing of state.
 */

/** The selection actions the host screen performs (they need
 * the screen's ViewModels, scope or navigation). The selected
 * text travels with the action — assembled from the model,
 * once, at tap time. */
enum class ReaderSelectionAction {
    REPLACE, TRANSLATE, DICTIONARY, SAVE_WORD, ASK_AI, ILLUSTRATE
}

/** Code 118: the bar's colour row speaks ARGB (its five
 * tints, SelectionBar §7.2); the core Highlight store speaks
 * token names ([Highlight.color]). This pair is the one
 * translation between them — the host seeds the row's
 * active tint from a stored token, and the screen's
 * fulfilment writes a picked tint back as its token. An
 * unknown token reads as amber, the store's own default. */
fun highlightTokenForTint(tintArgb: Int): String =
    when (tintArgb) {
        HIGHLIGHT_TINT_ARGB -> "violet"
        0xFFFFB020.toInt() -> "amber"
        0xFF99AA77.toInt() -> "sage"
        0xFFFF9200.toInt() -> "orange"
        0xFFCC99C0.toInt() -> "pink"
        else -> "amber"
    }

/** The stored token's bar tint (see [highlightTokenForTint]).
 * The sixth token, "scheme" (code 120 F1), names the active
 * design's own default tint: it is written only under
 * Material 3 and resolved against the design tint at the two
 * read sites (the DocumentReader wash and this host's
 * existing-episode seed). This pure mapping keeps the shipped
 * violet as its fallback, so wherever the design tint IS the
 * violet — Nocturne — a "scheme" row renders exactly the
 * shipped value. */
fun highlightTintForToken(token: String): Int =
    when (token) {
        "violet" -> HIGHLIGHT_TINT_ARGB
        "scheme" -> HIGHLIGHT_TINT_ARGB
        "sage" -> 0xFF99AA77.toInt()
        "orange" -> 0xFFFF9200.toInt()
        "pink" -> 0xFFCC99C0.toInt()
        else -> 0xFFFFB020.toInt()
    }

/** Positions the menu above the selection rect (below it when
 * the top has no room), clamped inside the window. The rect
 * arrives in window coordinates — the coordinates the popup
 * positions in. One instance per host composition;
 * [calculatePosition] reads the LIVE rect through
 * [anchorRect], so a range change re-seats the Popup. */
private class SelectionMenuPositionProvider(
    private val anchorRect: () -> Rect
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val rect = anchorRect()
        val gap = 10
        val maxX = maxOf(0, windowSize.width - popupContentSize.width)
        val x = rect.left.toInt().coerceIn(0, maxX)
        val above = rect.top.toInt() - popupContentSize.height - gap
        val y = if (above >= 0) {
            above
        } else {
            (rect.bottom.toInt() + gap)
                .coerceAtMost(maxOf(0, windowSize.height - popupContentSize.height))
        }
        return IntOffset(x, y)
    }
}

/**
 * Hosts the selection bar over one scroll reader's content.
 * The bar composes while an episode lives — a live
 * [selection], or (code 118) a tapped stored highlight in
 * [existingHighlight] — modulo the §7.3 dismiss fade; its
 * seat rect comes from the model's own geometry
 * ([selectionRect] — the union rect of the selection in
 * window coordinates, §7.2; the existing episode's stored
 * range arrives through the same door).
 *
 * Action routing (§7.3):
 * - Completing actions (Copy · Highlight · Bookmark ·
 *   Save word · Share) fire their effect at tap, run the
 *   150 ms dismiss fade, and then clear the model.
 * - Dialog-opening actions (Note · Dictionary · Translate ·
 *   Ask AI · Replace) clear the model as the dialog opens —
 *   the dialog layer outranks, and a note editor opened from
 *   the bar survives the clear its own action caused
 *   (§3.4 consequence 5).
 * - Select all does not dismiss: the model becomes the whole
 *   chapter the selection started in and the bar re-seats.
 */
@Composable
fun ReaderSelectionMenuHost(
    /** The model — the bar exists iff this is non-null. */
    selection: ReaderTextSelection?,
    /** §7.2: the selection's union rect in window
     * coordinates, read live by the position provider. */
    selectionRect: () -> Rect,
    /** §7.3 + Revision 1 §7.1: the model's selected text,
     * assembled by ReaderScreen's helper; Copy/Share consume
     * it here, every other text arm passes it through
     * [onAction]. */
    selectionText: () -> String,
    onAction: (ReaderSelectionAction, String) -> Unit,
    /** §8: the true-range arms (Highlight · Note · Bookmark),
     * fulfilled by the caller against the model's range. */
    onRangeAction: (ParagraphAction) -> Unit = {},
    /** Code 118: the stored highlight whose tap opened the
     * bar in its existing-highlight state (Revision 1 §7.2's
     * deferral reversed; the PDF surfaces' existingBar
     * mirrored). Non-null only while no live selection
     * lives — the two episodes are mutually exclusive, and
     * the seat rect for this episode arrives through the
     * same [selectionRect] door. */
    existingHighlight: Highlight? = null,
    /** Code 118: the colour row's apply — a colour-circle
     * tap, or Highlight tapped with the row already open.
     * The caller fulfils the tint: a new highlight in that
     * colour for the live model, a re-tint of the stored
     * row for the existing state. */
    onApplyHighlight: (tintArgb: Int) -> Unit = {},
    /** Code 118: the existing state's trash slot. */
    onRemoveHighlight: () -> Unit = {},
    /** Code 118: the existing state's Note arm — the note
     * editor opens over the stored highlight. */
    onEditExistingNote: (Highlight) -> Unit = {},
    /** Code 118: the existing episode's one clear. */
    onClearExisting: () -> Unit = {},
    /** §7.3: Select all — the model becomes the starting
     * chapter; the bar re-seats, it does not dismiss. */
    onSelectAll: () -> Unit = {},
    /** §3.4: the one clear — the model (tint, handles, bar)
     * dies by this single door. */
    onClearSelection: () -> Unit,
    /** The back chain's report (§7.4): true exactly while
     * an episode — live or existing — is open. */
    onSelectionOpenChanged: (Boolean) -> Unit = {},
    motionEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val currentSelection by rememberUpdatedState(selection)
    val currentExisting by rememberUpdatedState(existingHighlight)

    // §7.3's dismiss fade: a completing action's effect fires
    // at tap; the model clears 150 ms later, as the fade
    // completes. The fade is bound to the selection it began
    // on — a different selection living when the timer ends
    // is never cleared by a stale fade. Code 118: the
    // existing-highlight episode fades through its own
    // clear, on the same rule.
    var barDismissing by remember { mutableStateOf(false) }
    LaunchedEffect(barDismissing) {
        if (barDismissing) {
            val dismissingSelection = currentSelection
            val dismissingExisting = currentExisting
            delay(150)
            if (dismissingSelection != null &&
                currentSelection == dismissingSelection
            ) {
                onClearSelection()
            } else if (dismissingExisting != null &&
                currentExisting == dismissingExisting
            ) {
                onClearExisting()
            }
            barDismissing = false
        }
    }
    // §7.4 + code 118: the back chain's selection layer is
    // `selection != null || existingHighlight != null`,
    // reported through the existing door — an open
    // existing-highlight bar is a selection layer the chain
    // must see (the PDF bodies' selectionLayerOpen).
    val open = selection != null || existingHighlight != null
    LaunchedEffect(open) { onSelectionOpenChanged(open) }

    // The bar's state, hoisted here. Code 118: the colour
    // row is ENABLED in this host (Revision 1 §7.2's disable
    // is reversed — the store carried a colour token all
    // along, and the renderer reads it now), and a tapped
    // stored highlight seeds the existing state with its
    // own tint active (the PDF hosts' §7.4 entry). A live
    // selection always starts from the clean default. The
    // state resets whenever the episode closes.
    var barState by remember { mutableStateOf(SelectionBarState()) }
    LaunchedEffect(open) {
        if (!open) barState = SelectionBarState()
    }
    // Code 120 F1: the design default, hoisted in composition
    // because the seeding effect below is not composable. A
    // "scheme" row seeds with it, so the colour row's active
    // ring lands on circle 1 — the same value the bar applied.
    // Under Nocturne the resolver returns HIGHLIGHT_TINT_ARGB,
    // the value highlightTintForToken's "scheme" fallback
    // yields, so the seed is the shipped one.
    val designDefaultTintArgb = defaultHighlightTintArgb()
    LaunchedEffect(existingHighlight?.id, selection != null) {
        barState = when {
            existingHighlight != null -> SelectionBarState(
                existingHighlight = true,
                activeTintArgb =
                    if (existingHighlight.color == "scheme") {
                        designDefaultTintArgb
                    } else {
                        highlightTintForToken(existingHighlight.color)
                    }
            )
            selection != null -> SelectionBarState()
            else -> barState
        }
    }

    // Code 118: the active episode's text and clear. In the
    // existing state the text is the stored highlight's own
    // quote (there is no model to assemble from) and
    // dismissal runs through the existing clear; the arms
    // below are shared by both episodes.
    fun activeText(): String =
        if (selection != null) {
            selectionText()
        } else {
            existingHighlight?.quotedText ?: ""
        }

    fun dismissNow() {
        if (selection != null) {
            onClearSelection()
        } else {
            onClearExisting()
        }
    }

    fun barAction(action: SelectionBarAction) {
        when (action) {
            // Copy: our own write of the episode's text;
            // nothing is read back, ever (§7.3).
            SelectionBarAction.COPY -> {
                clipboardManager.setText(
                    AnnotatedString(activeText())
                )
                barDismissing = true
            }
            // The true-range arms: no text, no clipboard — the
            // caller fulfils against the model's range (§8).
            // (With the colour row enabled the bar routes
            // Highlight taps to the row and its apply door;
            // this arm remains the row-disabled fallback.)
            SelectionBarAction.HIGHLIGHT -> {
                barDismissing = true
                onRangeAction(ParagraphAction.HIGHLIGHT)
            }
            SelectionBarAction.BOOKMARK -> {
                barDismissing = true
                onRangeAction(ParagraphAction.BOOKMARK)
            }
            SelectionBarAction.NOTE -> {
                val existing = existingHighlight
                if (selection == null && existing != null) {
                    // Code 118: the note editor over the
                    // stored highlight (the PDF §7.4 arm);
                    // the episode clears as the dialog opens.
                    onEditExistingNote(existing)
                    onClearExisting()
                } else {
                    onRangeAction(ParagraphAction.NOTE)
                    onClearSelection()
                }
            }
            SelectionBarAction.DICTIONARY -> {
                onAction(
                    ReaderSelectionAction.DICTIONARY, activeText()
                )
                dismissNow()
            }
            SelectionBarAction.TRANSLATE -> {
                onAction(
                    ReaderSelectionAction.TRANSLATE, activeText()
                )
                dismissNow()
            }
            SelectionBarAction.ASK_AI -> {
                onAction(ReaderSelectionAction.ASK_AI, activeText())
                dismissNow()
            }
            // Code 124 (A7): Illustrate paints exactly the
            // selected passage — a dialog-class arm like
            // Ask AI (the AI page opens over the result).
            SelectionBarAction.ILLUSTRATE -> {
                onAction(
                    ReaderSelectionAction.ILLUSTRATE, activeText()
                )
                dismissNow()
            }
            SelectionBarAction.REPLACE -> {
                onAction(ReaderSelectionAction.REPLACE, activeText())
                dismissNow()
            }
            SelectionBarAction.SAVE_WORD -> {
                onAction(ReaderSelectionAction.SAVE_WORD, activeText())
                barDismissing = true
            }
            SelectionBarAction.SHARE -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, activeText())
                }
                context.startActivity(
                    Intent.createChooser(intent, null)
                )
                barDismissing = true
            }
            // Select all re-seats rather than dismisses (§7.3).
            SelectionBarAction.SELECT_ALL -> onSelectAll()
        }
    }

    content()
    if (selection == null && existingHighlight == null) return
    // One position provider per host composition, reading the
    // live model rect — a range change re-seats the Popup
    // without recomposing its content.
    val positionProvider = remember {
        SelectionMenuPositionProvider(selectionRect)
    }
    Popup(
        popupPositionProvider = positionProvider,
        // Dismissal is exclusively the model's (§7.2): the
        // Popup is non-focusable and dismisses nothing itself;
        // a dismiss request, if one ever arrives, routes into
        // the active episode's one clear.
        onDismissRequest = { dismissNow() },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        val seatMotion = rememberSelectionBarSeatMotion(
            visible = true,
            dismissing = barDismissing,
            motionEnabled = motionEnabled
        )
        // The owner's surviving items keep HIS relative order
        // — Translate · Ask AI · Share — and the legacy items
        // follow. Select all is always present now: it is the
        // model's own arm, not a manager callback that may or
        // may not exist. Code 118: in the existing state the
        // More list is the PDF bar's own — Translate · Ask AI
        // · Share, the arms that act on a stored passage's
        // text; the model-only arms (Replace · Save word ·
        // Select all · Bookmark here) belong to a live
        // selection and are not offered over a stored
        // highlight.
        val moreItems = if (selection == null &&
            existingHighlight != null
        ) {
            listOf(
                SelectionBarMoreItem(
                    SelectionBarAction.TRANSLATE, "Translate"
                ),
                SelectionBarMoreItem(
                    SelectionBarAction.ASK_AI, "Ask AI"
                ),
                SelectionBarMoreItem(
                    SelectionBarAction.ILLUSTRATE, "Illustrate"
                ),
                SelectionBarMoreItem(
                    SelectionBarAction.SHARE, "Share"
                )
            )
        } else {
            listOf(
                SelectionBarMoreItem(
                    SelectionBarAction.TRANSLATE, "Translate"
                ),
                SelectionBarMoreItem(
                    SelectionBarAction.ASK_AI, "Ask AI"
                ),
                SelectionBarMoreItem(
                    SelectionBarAction.ILLUSTRATE, "Illustrate"
                ),
                SelectionBarMoreItem(
                    SelectionBarAction.SHARE, "Share"
                ),
                SelectionBarMoreItem(
                    SelectionBarAction.REPLACE, "Replace"
                ),
                SelectionBarMoreItem(
                    SelectionBarAction.SAVE_WORD, "Save word"
                ),
                SelectionBarMoreItem(
                    SelectionBarAction.SELECT_ALL, "Select all"
                ),
                SelectionBarMoreItem(
                    SelectionBarAction.BOOKMARK, "Bookmark here"
                )
            )
        }
        SelectionBar(
            state = barState,
            onStateChange = { barState = it },
            moreItems = moreItems,
            callbacks = SelectionBarCallbacks(
                onAction = { action ->
                    if (!barDismissing) barAction(action)
                },
                // Code 118: the colour row's apply — the
                // caller fulfils the tint (a new highlight in
                // that colour for the live model; a re-tint
                // of the stored row for the existing state),
                // then the episode fades, exactly as the PDF
                // hosts run it.
                onApplyHighlight = { tintArgb, _ ->
                    if (!barDismissing) {
                        onApplyHighlight(tintArgb)
                        barDismissing = true
                    }
                },
                // Code 118: the existing state's trash slot.
                onRemoveHighlight = {
                    if (!barDismissing) {
                        onRemoveHighlight()
                        barDismissing = true
                    }
                },
                onDismiss = {
                    barDismissing = false
                    dismissNow()
                }
            ),
            // §7.2: always empty — Highlight / Note / Bookmark
            // can never vanish mid-selection.
            hiddenActions = emptySet(),
            modifier = Modifier.graphicsLayer {
                alpha = seatMotion.alpha
                scaleX = seatMotion.scale
                scaleY = seatMotion.scale
            }
        )
    }
}

/**
 * The Dictionary result, presented exactly as the EPUB
 * reader presents it: the word as the title, the definition
 * — or the same "no entry" line — as the body.
 *
 * Code 127 (audit C9) note: shared by both faces' readers on
 * purpose — title + body + TextButton actions under the
 * active theme root is both faces' dressed dialog anatomy,
 * and it holds no face-specific control to branch (its EPUB
 * twin at EpubSelection carries the same shape).
 */
@Composable
fun DictionaryResultDialog(
    word: String,
    definition: String?,
    onDismiss: () -> Unit,
    /** Code 122 (S25, audit #21): "Save to vocabulary" — the
     * host wires the reader's existing vocabulary save path
     * (ReaderViewModel.saveWord, the SAVE_WORD selection arm's
     * call). Null = no save door is drawn (no dead button).
     * NOTE (audit #21, pronunciation half): the dictionary
     * result carries NO pronunciation — DictionaryLookup
     * returns the definition String only (its file format is
     * `word<TAB>definition`) — so no pronunciation line can be
     * rendered; that half is reported DEVIATED, not faked. */
    onSaveToVocabulary: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(word) },
        text = { Text(definition ?: "No dictionary entry for this word yet.") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = if (onSaveToVocabulary != null) {
            {
                TextButton(
                    onClick = {
                        onSaveToVocabulary()
                        onDismiss()
                    }
                ) { Text("Save to vocabulary") }
            }
        } else {
            null
        }
    )
}

/** The selection Translate result: a spinner while the
 * translation runs, then the translated text (or the plain
 * verdict/error line the helper returned). */
@OptIn(
    androidx.compose.material3
        .ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun SelectionTranslateDialog(
    original: String,
    translated: String?,
    onDismiss: () -> Unit,
    /** Code 122 (S26, audit #22): the provider that answered,
     * carried from the translation result's `modelUsed`
     * ("Provider · model" — the provider is its head). Shown
     * as the spec's provenance AssistChip once a translation
     * lands; null while running and for verdict/error lines
     * (no provider answered, so no chip is claimed). */
    providerName: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Translation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    original,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )
                if (translated == null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        // Code 128 (audit C10): the loading
                        // state wears the face — the M3
                        // LoadingIndicator in the Material 3
                        // face (its loading language, StateKit),
                        // the plain spinner in Nocturne.
                        if (app.xreader.ui.theme.isEditorialDesign()) {
                            androidx.compose.material3.LoadingIndicator()
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(2.dp)
                            )
                        }
                        Text("Translating…")
                    }
                } else {
                    // Code 122 (S26): the translated line is
                    // bodyLarge, with the provider provenance
                    // as an assist chip beneath it.
                    Text(
                        translated,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (providerName != null) {
                        // Code 127 (audit C10): the provenance
                        // chip wears the face — the kit chip in
                        // Nocturne, the stock AssistChip in the
                        // Material 3 face.
                        if (app.xreader.ui.theme.isEditorialDesign()) {
                            androidx.compose.material3.AssistChip(
                                onClick = {},
                                label = { Text(providerName) }
                            )
                        } else {
                            app.xreader.ui.common.NocturneChip(
                                label = providerName
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * Translates ONE selected passage into English on the app's
 * existing translation client — the same [AiClient] the
 * chapter translator (RawTranslator) runs on, called the same
 * way (generateText, temperature 0.2). There is no
 * selection-level translation seam in the app today;
 * RawTranslator itself is chapter-scoped (it caches whole
 * chapters beside the book), so this is the smallest honest
 * version on that client: same feature gate
 * ([AiFeature.RAW_TRANSLATION]), same language detection
 * ([LanguageDetect]), same "already English" honesty, and
 * failures surface as the app's plain-words error text —
 * never a raw provider message.
 *
 * Returns the text to SHOW: the translation, or the verdict
 * / error line.
 */
/** Code 122 (S26): a selection translation plus the provider
 * that answered — the hub's result `modelUsed` is
 * "Provider · model", so the provider is its head. Null when
 * no provider answered (feature off, already-English verdict,
 * or an error line). */
data class SelectionTranslationResult(
    val text: String,
    val providerName: String?
)

suspend fun translateSelectionText(container: AppContainer, text: String): String =
    translateSelectionWithProvenance(container, text).text

suspend fun translateSelectionWithProvenance(
    container: AppContainer,
    text: String
): SelectionTranslationResult {
    val settings = container.aiPrefs.settings.first()
    if (!settings.enabled(AiFeature.RAW_TRANSLATION)) {
        return SelectionTranslationResult(
            "RAW translation is turned off — switch it on in " +
                "Providers & keys.",
            null
        )
    }
    val language = LanguageDetect.detect(text)
    if (language == SourceLanguage.ENGLISH) {
        return SelectionTranslationResult(
            "This already reads as English — nothing to translate.",
            null
        )
    }
    val system = if (language == SourceLanguage.OTHER) {
        "You are a literary translator. Translate the following " +
            "web-novel text into natural, readable English. " +
            "Answer with ONLY the translated text."
    } else {
        "You are a literary translator. Translate the following " +
            "${language.label} web-novel text into natural, readable " +
            "English. Answer with ONLY the translated text."
    }
    return when (
        val result = container.aiHub.client.generateText(
            prompt = text,
            system = system,
            temperature = 0.2f
        )
    ) {
        is AppResult.Ok -> SelectionTranslationResult(
            result.value.text.trim()
                .ifBlank { "The translator returned nothing for this passage." },
            result.value.modelUsed.substringBefore(" · ")
                .trim().ifBlank { null }
        )
        is AppResult.Err -> SelectionTranslationResult(
            ErrorText.displayText(result.error),
            null
        )
    }
}
