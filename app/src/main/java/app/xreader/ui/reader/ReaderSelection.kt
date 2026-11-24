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
import app.xreader.data.prefs.AiFeature
import app.xreader.di.AppContainer
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
    REPLACE, TRANSLATE, DICTIONARY, SAVE_WORD, ASK_AI
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
 * The bar composes iff [selection] is non-null (modulo the
 * §7.3 dismiss fade); its seat rect comes from the model's
 * own geometry ([selectionRect] — the union rect of the
 * selection in window coordinates, §7.2).
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
    /** §7.3: Select all — the model becomes the starting
     * chapter; the bar re-seats, it does not dismiss. */
    onSelectAll: () -> Unit = {},
    /** §3.4: the one clear — the model (tint, handles, bar)
     * dies by this single door. */
    onClearSelection: () -> Unit,
    /** The back chain's report (§7.4): true exactly while
     * [selection] is non-null. */
    onSelectionOpenChanged: (Boolean) -> Unit = {},
    motionEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val currentSelection by rememberUpdatedState(selection)

    // §7.3's dismiss fade: a completing action's effect fires
    // at tap; the model clears 150 ms later, as the fade
    // completes. The fade is bound to the selection it began
    // on — a different selection living when the timer ends
    // is never cleared by a stale fade.
    var barDismissing by remember { mutableStateOf(false) }
    LaunchedEffect(barDismissing) {
        if (barDismissing) {
            val dismissing = currentSelection
            delay(150)
            if (currentSelection == dismissing) onClearSelection()
            barDismissing = false
        }
    }
    // §7.4: the back chain's selection layer is exactly
    // `selection != null`, reported through the existing door.
    val open = selection != null
    LaunchedEffect(open) { onSelectionOpenChanged(open) }

    // The bar's state, hoisted here: the colour row stays
    // DISABLED in this host (the core reader's Highlight model
    // carries no per-highlight tint — an unchanged data
    // limitation), and `existingHighlight` stays false — the
    // core bar has no existing-highlight state (Revision 1
    // §7.2). The state resets whenever the model clears.
    var barState by remember {
        mutableStateOf(SelectionBarState(colourRowEnabled = false))
    }
    LaunchedEffect(open) {
        if (!open) {
            barState = SelectionBarState(colourRowEnabled = false)
        }
    }

    fun barAction(action: SelectionBarAction) {
        when (action) {
            // Copy: our own write of the model's text; nothing
            // is read back, ever (§7.3).
            SelectionBarAction.COPY -> {
                clipboardManager.setText(
                    AnnotatedString(selectionText())
                )
                barDismissing = true
            }
            // The true-range arms: no text, no clipboard — the
            // caller fulfils against the model's range (§8).
            SelectionBarAction.HIGHLIGHT -> {
                barDismissing = true
                onRangeAction(ParagraphAction.HIGHLIGHT)
            }
            SelectionBarAction.BOOKMARK -> {
                barDismissing = true
                onRangeAction(ParagraphAction.BOOKMARK)
            }
            SelectionBarAction.NOTE -> {
                onRangeAction(ParagraphAction.NOTE)
                onClearSelection()
            }
            SelectionBarAction.DICTIONARY -> {
                onAction(
                    ReaderSelectionAction.DICTIONARY, selectionText()
                )
                onClearSelection()
            }
            SelectionBarAction.TRANSLATE -> {
                onAction(
                    ReaderSelectionAction.TRANSLATE, selectionText()
                )
                onClearSelection()
            }
            SelectionBarAction.ASK_AI -> {
                onAction(ReaderSelectionAction.ASK_AI, selectionText())
                onClearSelection()
            }
            SelectionBarAction.REPLACE -> {
                onAction(ReaderSelectionAction.REPLACE, selectionText())
                onClearSelection()
            }
            SelectionBarAction.SAVE_WORD -> {
                onAction(ReaderSelectionAction.SAVE_WORD, selectionText())
                barDismissing = true
            }
            SelectionBarAction.SHARE -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, selectionText())
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
    if (selection == null) return
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
        // the one clear.
        onDismissRequest = { onClearSelection() },
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
        // may not exist.
        val moreItems = listOf(
            SelectionBarMoreItem(
                SelectionBarAction.TRANSLATE, "Translate"
            ),
            SelectionBarMoreItem(
                SelectionBarAction.ASK_AI, "Ask AI"
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
        SelectionBar(
            state = barState,
            onStateChange = { barState = it },
            moreItems = moreItems,
            callbacks = SelectionBarCallbacks(
                onAction = { action ->
                    if (!barDismissing) barAction(action)
                },
                // The colour row is disabled in this host
                // (colourRowEnabled = false), so this never fires.
                onApplyHighlight = { _, _ -> },
                onDismiss = {
                    barDismissing = false
                    onClearSelection()
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
 */
@Composable
fun DictionaryResultDialog(
    word: String,
    definition: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(word) },
        text = { Text(definition ?: "No dictionary entry for this word yet.") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** The selection Translate result: a spinner while the
 * translation runs, then the translated text (or the plain
 * verdict/error line the helper returned). */
@Composable
fun SelectionTranslateDialog(
    original: String,
    translated: String?,
    onDismiss: () -> Unit
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
                        CircularProgressIndicator(
                            modifier = Modifier.padding(2.dp)
                        )
                        Text("Translating…")
                    }
                } else {
                    Text(translated)
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
suspend fun translateSelectionText(container: AppContainer, text: String): String {
    val settings = container.aiPrefs.settings.first()
    if (!settings.enabled(AiFeature.RAW_TRANSLATION)) {
        return "RAW translation is turned off — switch it on in " +
            "Providers & keys."
    }
    val language = LanguageDetect.detect(text)
    if (language == SourceLanguage.ENGLISH) {
        return "This already reads as English — nothing to translate."
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
        is AppResult.Ok -> result.value.text.trim()
            .ifBlank { "The translator returned nothing for this passage." }
        is AppResult.Err -> ErrorText.displayText(result.error)
    }
}
