package app.xreader.ui.reader

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
 * The menu (owner's order): Copy · Replace · Translate ·
 * Dictionary · Save word · Ask AI · Share · Select all.
 * Copy and Select all are the manager's own callbacks.
 * Replace is an OFFER only — it opens a dialog and nothing
 * is written until that dialog's Save (see ReplaceTermDialog).
 * Translate / Dictionary / Save word / Ask AI are reported to
 * the host screen, which runs them through the app's existing
 * machinery. Highlight / Note are deliberately NOT here: the
 * novel reader's highlights are anchored to chapter char
 * offsets, this surface yields the selected TEXT only (no
 * offsets), and passage-level Highlight / Note already live
 * on the paragraph long-press menu in this same reader.
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
 * composition thread, so plain snapshot state is the bridge. */
private class ReaderTextToolbar : TextToolbar {

    class Request(
        val rect: Rect,
        val onCopyRequested: (() -> Unit)?,
        val onSelectAllRequested: (() -> Unit)?
    )

    var request by mutableStateOf<Request?>(null)
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
        request = Request(rect, onCopyRequested, onSelectAllRequested)
    }

    override fun hide() {
        request = null
    }
}

/** Positions the menu above the selection rect (below it when
 * the top has no room), clamped inside the window. The rect
 * arrives in the compose view's root coordinates, which are
 * the window coordinates the popup positions in. */
private class SelectionMenuPositionProvider(
    private val anchorRect: Rect
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val gap = 10
        val maxX = maxOf(0, windowSize.width - popupContentSize.width)
        val x = anchorRect.left.toInt().coerceIn(0, maxX)
        val above = anchorRect.top.toInt() - popupContentSize.height - gap
        val y = if (above >= 0) {
            above
        } else {
            (anchorRect.bottom.toInt() + gap)
                .coerceAtMost(maxOf(0, windowSize.height - popupContentSize.height))
        }
        return IntOffset(x, y)
    }
}

/**
 * Wraps one scroll reader's SelectionContainer: provides the
 * custom [TextToolbar] to that subtree and renders the menu
 * popup while the selection manager asks for it.
 */
@Composable
fun ReaderSelectionMenuHost(
    onAction: (ReaderSelectionAction, String) -> Unit,
    content: @Composable () -> Unit
) {
    val toolbar = remember { ReaderTextToolbar() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // The on-demand fetch (see the file header): run the
    // manager's copy, then read the selection back off the
    // clipboard. Blank means the fetch failed — the action is
    // dropped rather than run on a guess.
    fun fetchSelectedText(request: ReaderTextToolbar.Request): String? {
        request.onCopyRequested?.invoke()
        return clipboardManager.getText()?.text?.takeIf { it.isNotBlank() }
    }

    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        content()
        val request = toolbar.request ?: return@CompositionLocalProvider
        val positionProvider = remember(request.rect) {
            SelectionMenuPositionProvider(request.rect)
        }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = { toolbar.hide() },
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
                tonalElevation = 3.dp
            ) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        SelectionMenuItem("Copy") {
                            request.onCopyRequested?.invoke()
                            toolbar.hide()
                        }
                        SelectionMenuItem("Replace") {
                            val text = fetchSelectedText(request)
                            toolbar.hide()
                            if (text != null) {
                                onAction(ReaderSelectionAction.REPLACE, text)
                            }
                        }
                        SelectionMenuItem("Translate") {
                            val text = fetchSelectedText(request)
                            toolbar.hide()
                            if (text != null) {
                                onAction(ReaderSelectionAction.TRANSLATE, text)
                            }
                        }
                        SelectionMenuItem("Dictionary") {
                            val text = fetchSelectedText(request)
                            toolbar.hide()
                            if (text != null) {
                                onAction(ReaderSelectionAction.DICTIONARY, text)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        SelectionMenuItem("Save word") {
                            val text = fetchSelectedText(request)
                            toolbar.hide()
                            if (text != null) {
                                onAction(ReaderSelectionAction.SAVE_WORD, text)
                            }
                        }
                        SelectionMenuItem("Ask AI") {
                            val text = fetchSelectedText(request)
                            toolbar.hide()
                            if (text != null) {
                                onAction(ReaderSelectionAction.ASK_AI, text)
                            }
                        }
                        SelectionMenuItem("Share") {
                            val text = fetchSelectedText(request)
                            toolbar.hide()
                            if (text != null) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, null)
                                )
                            }
                        }
                        if (request.onSelectAllRequested != null) {
                            SelectionMenuItem("Select all") {
                                request.onSelectAllRequested.invoke()
                                toolbar.hide()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One compact menu entry — a plain tappable label, sized
 * like the stock floating toolbar's items. */
@Composable
private fun SelectionMenuItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    )
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
