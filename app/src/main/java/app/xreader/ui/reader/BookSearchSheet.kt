package app.xreader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.xreader.ui.common.SectionHeader

/**
 * Code 122 (S20, audit #19): the snippet with its match in
 * emphasized weight — the first case-insensitive occurrence
 * of [query] in [snippet] is bolded; no query / no match
 * renders the snippet plain.
 */
fun emphasizedSnippet(snippet: String, query: String): AnnotatedString {
    val needle = query.trim()
    if (needle.isEmpty()) return AnnotatedString(snippet)
    val at = snippet.indexOf(needle, ignoreCase = true)
    if (at < 0) return AnnotatedString(snippet)
    return buildAnnotatedString {
        append(snippet.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(snippet.substring(at, at + needle.length))
        }
        append(snippet.substring(at + needle.length))
    }
}

/**
 * v1.7 Patch 3 (Item 4b): the in-book search sheet on the
 * core road — the converted EPUB's replacement for the local
 * reader's search it leaves behind (and a new surface for
 * every core book). Type a word, Search (or the keyboard's
 * search action), tap a result to jump to that chapter at
 * that offset. One hit per chapter — the first match — the
 * same shape the local search presented per section.
 *
 * Results live in the ReaderViewModel (they survive the
 * sheet's recompositions and are dropped when the sheet is
 * dismissed); this sheet is a plain view over them.
 */
@Composable
fun BookSearchSheet(
    hits: List<CoreSearchHit>?,
    busy: Boolean,
    onSearch: (String) -> Unit,
    onJump: (CoreSearchHit) -> Unit
) {
    var query by remember { mutableStateOf("") }
    // Code 122 (S20): the query the shown hits belong to (the
    // match emphasized in each snippet is THIS query, even if
    // the field has since been edited).
    var submittedQuery by remember { mutableStateOf("") }
    // Code 122 (S20): the field auto-focuses on open.
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }
    val runSearch: () -> Unit = {
        submittedQuery = query
        onSearch(query)
    }
    // Code 121 (S20): under M3 the sheet head carries the
    // result count and results are list items; the search
    // behaviour (one hit per chapter, tap lands and closes)
    // is unchanged.
    val searchIsM3 = app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SectionHeader(
            title = if (searchIsM3 && hits != null) {
                "Search · ${hits.size} results"
            } else {
                "Search"
            },
            eyebrow = "This book"
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Word or phrase") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { runSearch() }
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocusRequester)
            )
            TextButton(
                onClick = { runSearch() },
                enabled = query.isNotBlank() && !busy
            ) {
                Text("Search")
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            busy -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(
                    "Searching the book…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            hits == null -> Text(
                "Search this book's text. One result per chapter — " +
                    "its first match.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            hits.isEmpty() -> Text(
                "No matches in this book.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            else -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(hits, key = { it.chapterId }) { hit ->
                    if (searchIsM3) {
                        ListItem(
                            headlineContent = { Text(hit.chapterTitle) },
                            supportingContent = {
                                Text(
                                    emphasizedSnippet(
                                        hit.snippet, submittedQuery
                                    )
                                )
                            },
                            modifier = Modifier.clickable { onJump(hit) }
                        )
                    } else {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onJump(hit) }
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(
                                hit.chapterTitle,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                hit.snippet,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
