package app.xreader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.domain.reader.ReplaceTarget
import app.xreader.domain.reader.TermPair

/**
 * Patch 11 (Part A): the Replace dialog — Moon+'s
 * selection-fed "Names replacement" moment in Xreader's
 * clothes, with the owner's pinned behaviour:
 *
 *  - Long-pressing a word only OFFERS Replace (in the
 *    selection menu). Tapping Replace opens THIS dialog and
 *    writes NOTHING.
 *  - The pair is saved ONLY when the user taps Save here.
 *    Dismissing or cancelling changes nothing — no pair
 *    written, no display change.
 *  - When the selection already belongs to a pair of this
 *    book ([ReplaceTarget.Existing], resolved by
 *    [app.xreader.domain.reader.resolveReplaceTarget]), the
 *    dialog opens THAT pair for editing and offers Remove —
 *    removing the pair is the revert: terms apply at display
 *    time only, so the book's own text comes back exactly.
 *
 * The fields are the TermsEditor's own (Original / Show as /
 * Match case / Whole word only), so a pair made here reads
 * the same as a pair made on the Terms page. Saving and
 * removing hand the WHOLE updated book list to [onSave] /
 * [onRemove], which write through TermsPrefs' per-book API —
 * the display re-derives instantly (ReaderViewModel's
 * reapplyTerms), with no engine change.
 */
@Composable
fun ReplaceTermDialog(
    target: ReplaceTarget,
    onDismiss: () -> Unit,
    /** Save: the book's full pair list with the pair added
     * (New) or replaced in place (Existing). */
    onSave: (List<TermPair>) -> Unit,
    /** Remove (Existing only): the book's full pair list with
     * the pair taken out. */
    onRemove: (List<TermPair>) -> Unit,
    /** The book's current pairs — the base the saved list is
     * built from. */
    bookPairs: List<TermPair>
) {
    val existing = target as? ReplaceTarget.Existing
    val initial = when (target) {
        is ReplaceTarget.Existing -> target.pair
        is ReplaceTarget.New -> TermPair(
            original = target.original,
            replacement = ""
        )
    }
    var draftOriginal by remember(target) {
        mutableStateOf(initial.original)
    }
    var draftReplacement by remember(target) {
        mutableStateOf(initial.replacement)
    }
    var draftMatchCase by remember(target) {
        mutableStateOf(initial.matchCase)
    }
    var draftWholeWord by remember(target) {
        mutableStateOf(initial.wholeWord)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (existing != null) "Replace — this book" else "Replace in this book")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draftOriginal,
                    onValueChange = { draftOriginal = it },
                    label = { Text("Original") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draftReplacement,
                    onValueChange = { draftReplacement = it },
                    label = { Text("Show as") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Match case",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = draftMatchCase,
                        onCheckedChange = { draftMatchCase = it }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Whole word only",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = draftWholeWord,
                        onCheckedChange = { draftWholeWord = it }
                    )
                }
                Text(
                    "Applies to this book only, and only to what " +
                        "you see — the book's own text is never changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = draftOriginal.isNotBlank(),
                onClick = {
                    val pair = TermPair(
                        original = draftOriginal.trim(),
                        replacement = draftReplacement,
                        matchCase = draftMatchCase,
                        wholeWord = draftWholeWord
                    )
                    onSave(
                        if (existing != null) {
                            bookPairs.mapIndexed { index, old ->
                                if (index == existing.index) pair else old
                            }
                        } else {
                            bookPairs + pair
                        }
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(
                        onClick = {
                            onRemove(
                                bookPairs.filterIndexed { index, _ ->
                                    index != existing.index
                                }
                            )
                        }
                    ) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
