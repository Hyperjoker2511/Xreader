package app.xreader.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.data.extension.DeclarativeConfig
import app.xreader.data.extension.ParserRulesEditor
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.XreaderPrimaryButton
import app.xreader.ui.theme.errorTextColor

/**
 * "Edit parser rules" (v1.0.4, D14): the stored rules of a
 * link/AI-built source as plain-labelled fields, grouped the way
 * the reading path uses them. Saving is gated in the ViewModel:
 * the edited candidate must pass the same test-drive every built
 * source faces before it may replace the working rules.
 */
@Composable
fun ParserRulesEditorScreen(
    config: DeclarativeConfig,
    saving: Boolean,
    message: String?,
    onSave: (ParserRulesEditor.Fields) -> Unit,
    onBack: () -> Unit
) {
    var fields by remember(config) {
        mutableStateOf(ParserRulesEditor.fieldsOf(config))
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Text(
                "Edit parser rules",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "These rules tell Xreader where things live on " +
                    "${config.name}'s pages. Change what you need, then " +
                    "“Test and save”: a real book is opened with your " +
                    "new rules first, and nothing is saved unless the " +
                    "test read passes. Your working rules stay in " +
                    "place the whole time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "A rule can have fallbacks: separate selectors with " +
                    "|| and the first one that finds something wins — " +
                    "for example “.old-list a || .new-list a”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ListingFieldsGroup(
                title = "Finding books — search",
                note = if (config.search == null) {
                    "This source has no search rules yet. Fill in the " +
                        "address, result, title and link rules to add " +
                        "search."
                } else {
                    null
                },
                fields = fields.search,
                onChange = { fields = fields.copy(search = it) }
            )
            ListingFieldsGroup(
                title = "Finding books — catalogue",
                note = if (config.popular == null) {
                    "This source has no catalogue rules yet. Fill in " +
                        "the address, result, title and link rules to " +
                        "add one."
                } else {
                    null
                },
                fields = fields.popular,
                onChange = { fields = fields.copy(popular = it) }
            )

            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Book page",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Blank is allowed here — Xreader falls back " +
                            "to what the book list showed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    RuleField("Book title", fields.detailsTitle) {
                        fields = fields.copy(detailsTitle = it)
                    }
                    RuleField("Author", fields.detailsAuthor) {
                        fields = fields.copy(detailsAuthor = it)
                    }
                    RuleField("Synopsis", fields.detailsDescription) {
                        fields = fields.copy(detailsDescription = it)
                    }
                    RuleField("Cover image", fields.detailsCover) {
                        fields = fields.copy(detailsCover = it)
                    }
                }
            }

            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Chapters",
                        style = MaterialTheme.typography.titleMedium
                    )
                    RuleField("Each chapter row", fields.chapterList) {
                        fields = fields.copy(chapterList = it)
                    }
                    RuleField("Chapter name", fields.chapterName) {
                        fields = fields.copy(chapterName = it)
                    }
                    RuleField("Chapter link", fields.chapterLink) {
                        fields = fields.copy(chapterLink = it)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Newest chapter first")
                            Text(
                                "On when the site lists the latest " +
                                    "chapter at the top — Xreader shows " +
                                    "reading order either way.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fields.chapterReverse,
                            onCheckedChange = {
                                fields = fields.copy(chapterReverse = it)
                            }
                        )
                    }
                }
            }

            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Chapter text",
                        style = MaterialTheme.typography.titleMedium
                    )
                    RuleField("Story text block", fields.contentSelector) {
                        fields = fields.copy(contentSelector = it)
                    }
                    RuleField(
                        "Chapter title (can be blank)",
                        fields.contentTitle
                    ) {
                        fields = fields.copy(contentTitle = it)
                    }
                    RuleField(
                        "Next page link (can be blank)",
                        fields.contentNextPage
                    ) {
                        fields = fields.copy(contentNextPage = it)
                    }
                    OutlinedTextField(
                        value = fields.contentRemove,
                        onValueChange = {
                            fields = fields.copy(contentRemove = it)
                        },
                        label = { Text("Blocks to remove — one per line") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            message?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = errorTextColor()
                )
                // v1.0.5 (E5): one-tap problem details. (A
                // message still on this screen is always a
                // failure — success closes the editor with a
                // snackbar instead.)
                val clipboard = androidx.compose.ui.platform
                    .LocalClipboardManager.current
                var detailsCopied by remember { mutableStateOf(false) }
                androidx.compose.material3.TextButton(onClick = {
                    clipboard.setText(
                        androidx.compose.ui.text.AnnotatedString(
                            app.xreader.data.extension.ProblemDetails.format(
                                host = app.xreader.data.extension
                                    .CookieParsing.hostOf(config.baseUrl),
                                failingStep = "Test read of the edited rules",
                                reason = text,
                                appVersion = app.xreader.BuildConfig.VERSION_NAME,
                                // v1.0.6.1 (P6): the session's
                                // problem history rides along.
                                recentProblems =
                                    app.xreader.data.extension
                                        .DiagnosticLog.entries()
                            )
                        )
                    )
                    detailsCopied = true
                }) {
                    Text(if (detailsCopied) "Copied ✓" else "Copy details")
                }
            }
            XreaderPrimaryButton(
                text = if (saving) "Testing…" else "Test and save",
                onClick = { onSave(fields) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ListingFieldsGroup(
    title: String,
    note: String?,
    fields: ParserRulesEditor.ListingFields,
    onChange: (ParserRulesEditor.ListingFields) -> Unit
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RuleField("Page address", fields.url) {
                onChange(fields.copy(url = it))
            }
            RuleField("Each result", fields.results) {
                onChange(fields.copy(results = it))
            }
            RuleField("Result title", fields.title) {
                onChange(fields.copy(title = it))
            }
            RuleField("Result link", fields.link) {
                onChange(fields.copy(link = it))
            }
            RuleField("Result cover (can be blank)", fields.cover) {
                onChange(fields.copy(cover = it))
            }
            RuleField("Result author (can be blank)", fields.author) {
                onChange(fields.copy(author = it))
            }
            RuleField("Next page marker (can be blank)", fields.nextPage) {
                onChange(fields.copy(nextPage = it))
            }
        }
    }
}

@Composable
private fun RuleField(
    label: String,
    value: String,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
