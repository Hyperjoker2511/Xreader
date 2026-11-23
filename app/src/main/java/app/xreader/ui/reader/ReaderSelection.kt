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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
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
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Patch 11 (Part A): the novel reader's selection menu — the
 * EPUB reader's custom selection-menu pattern
 * (ui/localreader/EpubSelection.kt) ported to the Compose
 * text surface.
 *
 * The pattern, in both readers: the app's actions ride the
 * PLATFORM selection menu instead of a poll or a parallel
 * surface, and the selected text is fetched ON DEMAND, once,
 * inside the handler of the action the user actually tapped —
 * selection costs nothing while the user is merely reading.
 *
 * The EPUB reader's surface is a WebView, so its menu is a
 * native ActionMode and its fetch is `currentSelection()`.
 * The novel reader's surface is a Compose [SelectionContainer]
 * (scroll modes), whose platform menu hook is [TextToolbar]:
 * providing a custom one through [LocalTextToolbar] replaces
 * the stock floating toolbar inside this subtree only. The
 * fetch rides the manager's own copy callback — tapping an
 * action first invokes `onCopyRequested` (the selection lands
 * on the clipboard, exactly as if the user had tapped Copy)
 * and then reads the clipboard back. There is no other public
 * seam to the selected text in Compose 1.7 (the selection
 * manager itself is not exposed), and the end state is the
 * same as the EPUB pattern's: one fetch, at tap time.
 *
 * The menu is the shared [SelectionBar] (SelectionBar
 * build): Copy · Highlight · Note · Dictionary · More, one
 * component with the PDF surfaces. Copy and Select all are
 * the manager's own callbacks. Highlight / Note / Bookmark
 * here are the PASSAGE actions: this surface yields the
 * selected TEXT only (no offsets), so they route through
 * the host's paragraph anchor — the block whose long-press
 * began the selection episode (DocumentReader §8.3) — and
 * are hidden when no anchor exists. Replace is an OFFER
 * only — it opens a dialog and nothing is written until
 * that dialog's Save (see ReplaceTermDialog). Translate /
 * Dictionary / Save word / Ask AI are reported to the host
 * screen, which runs them through the app's existing
 * machinery.
 */

/** The selection actions the host screen performs (they need
 * the screen's ViewModels, scope or navigation). The selected
 * text travels with the action — fetched once, at tap time. */
enum class ReaderSelectionAction {
    REPLACE, TRANSLATE, DICTIONARY, SAVE_WORD, ASK_AI
}

/** A state-driven [TextToolbar]: [showMenu] records the
 * request, the host composable renders it as a Compose popup,
 * [hide] clears it. The SelectionManager calls both on the
 * composition thread, so plain snapshot state is the bridge.
 *
 * SelectionBar (§8.2, as revised — the churn fix): the
 * toolbar keeps TWO channels. The manager's callbacks live
 * in a PLAIN holder, overwritten unconditionally on every
 * [showMenu] — writing it recomposes nothing, and the host
 * reads the callbacks through `rememberUpdatedState`
 * delegates over it. The state channel is [request] (the
 * episode marker: non-null while a menu episode lives) +
 * [anchorRect] (the Popup's live anchor). A rect-only
 * [showMenu] — the manager re-reporting the same selection
 * mid-drag — writes [anchorRect] ONLY (and only when the
 * rect moved ≥ 1px on either axis), so the Popup's CONTENT
 * never recomposes for a rect update; the guard compares
 * rects, never callback identity (callback equality is a
 * library internal this design does not depend on). */
private class ReaderTextToolbar : TextToolbar {

    class Request(
        val rect: Rect,
        val onCopyRequested: (() -> Unit)?,
        val onSelectAllRequested: (() -> Unit)?
    )

    /** The latest manager callbacks — plain fields, NOT
     * snapshot state: overwritten on every [showMenu],
     * read by the host through `rememberUpdatedState`. */
    var latestOnCopyRequested: (() -> Unit)? = null
    var latestOnSelectAllRequested: (() -> Unit)? = null

    var request by mutableStateOf<Request?>(null)
        private set

    /** The Popup's live anchor rect — the position provider
     * reads this state, so a write here re-seats the Popup
     * without recomposing its content. */
    var anchorRect by mutableStateOf(Rect.Zero)
        private set

    override val status: TextToolbarStatus
        get() = if (request != null) {
            TextToolbarStatus.Shown
        } else {
            TextToolbarStatus.Hidden
        }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        // Read-only text: paste/cut are never offered by the
        // selection manager here, and are not part of the menu.
        latestOnCopyRequested = onCopyRequested
        latestOnSelectAllRequested = onSelectAllRequested
        if (request == null) {
            // Episode opens: the marker + the anchor.
            request = Request(rect, onCopyRequested, onSelectAllRequested)
            anchorRect = rect
        } else if (abs(rect.left - anchorRect.left) >= 1f ||
            abs(rect.top - anchorRect.top) >= 1f ||
            abs(rect.right - anchorRect.right) >= 1f ||
            abs(rect.bottom - anchorRect.bottom) >= 1f
        ) {
            // Rect-only update: the anchor moves; the
            // episode marker (and the Popup's content) is
            // untouched.
            anchorRect = rect
        }
        // Sub-pixel / identical rect: write nothing.
    }

    override fun hide() {
        request = null
    }
}

/** Positions the menu above the selection rect (below it when
 * the top has no room), clamped inside the window. The rect
 * arrives in the compose view's root coordinates, which are
 * the window coordinates the popup positions in. One
 * instance per host composition; [calculatePosition] reads
 * the LIVE anchor rect through [anchorRect] (§8.2). */
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
 * Wraps one scroll reader's SelectionContainer: provides the
 * custom [TextToolbar] to that subtree and renders the menu
 * popup while the selection manager asks for it. The popup's
 * content is the shared [SelectionBar] (SelectionBar §8.1) —
 * the same component the PDF surfaces host.
 *
 * Action routing (§8.1, as revised — two groups):
 * - Group A, the ANCHOR arms (Highlight · Note · Bookmark
 *   here): no fetch, no clipboard — they fire
 *   [onParagraphAction] immediately; the caller resolves
 *   the passage from its episode anchor. They are visible
 *   only when the caller passes no [hiddenActions] for
 *   them (the anchor-null rule lives with the caller).
 * - Group B, the TEXT arms: fetch the selected text on
 *   demand (below), then run. Copy is the manager's own
 *   callback (no fetch); Select all likewise.
 */
@Composable
fun ReaderSelectionMenuHost(
    onAction: (ReaderSelectionAction, String) -> Unit,
    /** The passage arms, fulfilled by the caller from its
     * selection-episode anchor (§8.3). */
    onParagraphAction: (ParagraphAction) -> Unit = {},
    /** Actions this episode cannot run (the anchor-null
     * set), forwarded to the bar verbatim. */
    hiddenActions: Set<SelectionBarAction> = emptySet(),
    /** The back chain's report (§9.2): true while a menu
     * episode is open. */
    onSelectionOpenChanged: (Boolean) -> Unit = {},
    /** The back chain's clear nonce (§9.2): a bump hides
     * the menu immediately. */
    clearSelectionSignal: Int = 0,
    motionEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val toolbar = remember { ReaderTextToolbar() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // §8.2 (Critic F2): the manager's callbacks, read
    // through rememberUpdatedState delegates over the
    // toolbar's plain holder — a composed bar never acts on
    // a stale copy callback, even though the Popup was
    // composed at episode start.
    val currentOnCopyRequested by rememberUpdatedState(
        toolbar.latestOnCopyRequested
    )
    val currentOnSelectAllRequested by rememberUpdatedState(
        toolbar.latestOnSelectAllRequested
    )

    // §9.3 (as revised): a dismissal that follows an ACTION
    // fades the bar over 150ms before the toolbar hides —
    // the action's effect fires at tap time; only the hide
    // is delayed. The Popup's own dismiss paths (back,
    // click-outside) hide IMMEDIATELY and reset the flag.
    var menuDismissing by remember { mutableStateOf(false) }
    LaunchedEffect(menuDismissing) {
        if (menuDismissing) {
            delay(150)
            toolbar.hide()
            menuDismissing = false
        }
    }
    // §9.2: the screen's back chain clears this layer
    // through the nonce — immediate, no fade.
    LaunchedEffect(clearSelectionSignal) {
        if (clearSelectionSignal > 0) {
            menuDismissing = false
            toolbar.hide()
        }
    }
    val episodeOpen = toolbar.request != null
    LaunchedEffect(episodeOpen) { onSelectionOpenChanged(episodeOpen) }

    // The bar's state, hoisted here (§8.1): the colour row
    // is DISABLED in this host — the core reader's
    // Highlight model carries no per-highlight tint — so
    // Highlight routes straight to the anchor arm. The
    // state resets whenever an episode ends (Readiness
    // F6.3), the capability flag preserved across resets.
    var barState by remember {
        mutableStateOf(SelectionBarState(colourRowEnabled = false))
    }
    LaunchedEffect(episodeOpen) {
        if (!episodeOpen) {
            barState = SelectionBarState(colourRowEnabled = false)
        }
    }

    // The on-demand fetch (see the file header): run the
    // manager's copy, then read the selection back off the
    // clipboard. Blank means the fetch failed — the action
    // is dropped rather than run on a guess.
    fun fetchSelectedText(): String? {
        currentOnCopyRequested?.invoke()
        return clipboardManager.getText()?.text?.takeIf { it.isNotBlank() }
    }

    // Group B: fetch first, then dismiss, then run — a
    // blank fetch drops the action and hides at once
    // (today's rule); a good fetch starts the §9.3 fade
    // and the effect fires NOW.
    fun fetchArm(action: ReaderSelectionAction) {
        val text = fetchSelectedText()
        if (text == null) {
            toolbar.hide()
        } else {
            menuDismissing = true
            onAction(action, text)
        }
    }

    fun barAction(action: SelectionBarAction) {
        when (action) {
            // Copy: today's arm — the manager's own copy,
            // no fetch needed; the dismissal fades (§9.3).
            SelectionBarAction.COPY -> {
                currentOnCopyRequested?.invoke()
                menuDismissing = true
            }
            // Group A: the anchor arms — no fetch, no
            // clipboard; dismissal starts, the passage
            // action fires at once.
            SelectionBarAction.HIGHLIGHT -> {
                menuDismissing = true
                onParagraphAction(ParagraphAction.HIGHLIGHT)
            }
            SelectionBarAction.NOTE -> {
                menuDismissing = true
                onParagraphAction(ParagraphAction.NOTE)
            }
            SelectionBarAction.BOOKMARK -> {
                menuDismissing = true
                onParagraphAction(ParagraphAction.BOOKMARK)
            }
            SelectionBarAction.DICTIONARY ->
                fetchArm(ReaderSelectionAction.DICTIONARY)
            SelectionBarAction.TRANSLATE ->
                fetchArm(ReaderSelectionAction.TRANSLATE)
            SelectionBarAction.ASK_AI ->
                fetchArm(ReaderSelectionAction.ASK_AI)
            SelectionBarAction.REPLACE ->
                fetchArm(ReaderSelectionAction.REPLACE)
            SelectionBarAction.SAVE_WORD ->
                fetchArm(ReaderSelectionAction.SAVE_WORD)
            SelectionBarAction.SHARE -> {
                val text = fetchSelectedText()
                if (text == null) {
                    toolbar.hide()
                } else {
                    menuDismissing = true
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, null)
                    )
                }
            }
            // Select all RE-SEATS rather than dismisses: the
            // manager re-shows the menu for the widened
            // selection, so this arm keeps today's immediate
            // hide — a delayed hide would land after the
            // re-show and kill the live menu.
            SelectionBarAction.SELECT_ALL -> {
                currentOnSelectAllRequested?.invoke()
                toolbar.hide()
            }
        }
    }

    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        content()
        if (toolbar.request == null) return@CompositionLocalProvider
        // §8.2: ONE position provider per host composition,
        // reading the live anchorRect — a rect-only update
        // re-seats the Popup without recomposing its content.
        val positionProvider = remember {
            SelectionMenuPositionProvider { toolbar.anchorRect }
        }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = {
                menuDismissing = false
                toolbar.hide()
            },
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            val seatMotion = rememberSelectionBarSeatMotion(
                visible = true,
                dismissing = menuDismissing,
                motionEnabled = motionEnabled
            )
            // §3.3 (as revised): the owner's surviving items
            // keep HIS relative order — Translate · Ask AI ·
            // Share — and the legacy items follow.
            val moreItems = buildList {
                add(
                    SelectionBarMoreItem(
                        SelectionBarAction.TRANSLATE, "Translate"
                    )
                )
                add(
                    SelectionBarMoreItem(
                        SelectionBarAction.ASK_AI, "Ask AI"
                    )
                )
                add(
                    SelectionBarMoreItem(
                        SelectionBarAction.SHARE, "Share"
                    )
                )
                add(
                    SelectionBarMoreItem(
                        SelectionBarAction.REPLACE, "Replace"
                    )
                )
                add(
                    SelectionBarMoreItem(
                        SelectionBarAction.SAVE_WORD, "Save word"
                    )
                )
                if (currentOnSelectAllRequested != null) {
                    add(
                        SelectionBarMoreItem(
                            SelectionBarAction.SELECT_ALL, "Select all"
                        )
                    )
                }
                add(
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
                        if (!menuDismissing) barAction(action)
                    },
                    // The colour row is disabled in this host
                    // (colourRowEnabled = false), so this
                    // never fires.
                    onApplyHighlight = { _, _ -> },
                    onDismiss = { toolbar.hide() }
                ),
                hiddenActions = hiddenActions,
                modifier = Modifier.graphicsLayer {
                    alpha = seatMotion.alpha
                    scaleX = seatMotion.scale
                    scaleY = seatMotion.scale
                }
            )
        }
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
