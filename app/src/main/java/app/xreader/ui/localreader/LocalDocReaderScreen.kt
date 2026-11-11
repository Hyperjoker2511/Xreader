package app.xreader.ui.localreader

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.xreader.data.db.BookFormat
import app.xreader.data.engine.PdfSession
import app.xreader.data.engine.ReadiumEpubSession
import app.xreader.di.AppContainer

/**
 * v1.5.7 (Scopes B+C): the local-document reader's DISPATCHER.
 * This entry point stays exactly where the shell routes to it —
 * no route or shell changes — but it no longer renders a format
 * itself. Once a document is open it hands off by format:
 *
 *  - EPUB → [EpubBookReader]: a BOOK reader (flowing text,
 *    Readium book-mode settings, percent + chapter progress).
 *  - PDF → [PdfDocumentReader]: a DOCUMENT reader (the file's
 *    own fixed pages, page N-of-Total progress, zoom + render
 *    filter).
 *
 * What stays shared is only what the split allows: the open /
 * missing-file / password / failure states below, the library
 * entry that routed here, and the ViewModel's history, progress
 * and bookmark seams. The two readers share no settings sheet,
 * no progress semantics, no contents source and no gesture
 * model.
 *
 * Shell wiring (MainScaffold, owned by the coordinator):
 * ```
 * composable(
 *     "local/{bookId}?target={target}",
 *     arguments = listOf(
 *         navArgument("bookId") { type = NavType.LongType },
 *         navArgument("target") { type = NavType.StringType; nullable = true; defaultValue = null }
 *     )
 * ) { entry ->
 *     LocalDocReaderScreen(
 *         container = container,
 *         bookId = entry.arguments?.getLong("bookId") ?: 0L,
 *         startTargetJson = entry.arguments?.getString("target")
 *             ?.let { android.net.Uri.decode(it) },
 *         onClose = { navController.popBackStack() },
 *         onOpenBookPage = { navigateContent("book/$bookId") }
 *     )
 * }
 * ```
 * Callers navigate with
 * `"local/$bookId"` or
 * `"local/$bookId?target=${Uri.encode(targetJson)}"`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalDocReaderScreen(
    container: AppContainer,
    bookId: Long,
    startTargetJson: String?,
    onClose: () -> Unit,
    // v1.5.10 (F2 door): opens THIS book's page on the existing
    // "book/{bookId}" route (see the shell wiring above). The
    // shell supplies it; defaulted null so other callers keep
    // the honest absent-door behaviour (no "Book page" row in
    // either reader's Book information sheet).
    onOpenBookPage: (() -> Unit)? = null
) {
    val viewModel: LocalDocReaderViewModel = viewModel(
        key = "local-reader-$bookId",
        factory = LocalDocReaderViewModel.factory(container, bookId, startTargetJson)
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    var passwordOpen by remember { mutableStateOf(false) }

    when (val current = state) {
        is LocalReaderState.Ready -> when (current.session.format) {
            BookFormat.EPUB -> EpubBookReader(
                viewModel = viewModel,
                ready = current,
                session = current.session as ReadiumEpubSession,
                snackbar = snackbar,
                onClose = onClose,
                onOpenBookPage = onOpenBookPage
            )
            BookFormat.PDF -> PdfDocumentReader(
                viewModel = viewModel,
                ready = current,
                session = current.session as PdfSession,
                snackbar = snackbar,
                onClose = onClose,
                onOpenBookPage = onOpenBookPage
            )
            else -> StateScaffold(
                title = current.book.title,
                snackbar = snackbar,
                onClose = onClose
            ) {
                Text(
                    "This kind of file reads in the chapter reader.",
                    Modifier.padding(24.dp)
                )
            }
        }

        is LocalReaderState.Loading -> StateScaffold(
            title = "Opening…",
            snackbar = snackbar,
            onClose = onClose
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                // v1.5.8 (F4): the state names its phase.
                Text(current.phase)
            }
        }

        is LocalReaderState.FileMissing -> StateScaffold(
            title = current.book?.title ?: "File not found",
            snackbar = snackbar,
            onClose = onClose
        ) {
            FileMissingBody(viewModel = viewModel, onClose = onClose)
        }

        is LocalReaderState.PasswordRequired -> StateScaffold(
            title = current.book?.title ?: "Locked document",
            snackbar = snackbar,
            onClose = onClose
        ) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // v1.5.8 (H10): the prompt is offered on EVERY
                // Android version. On API 35+ the platform
                // renderer honours the password; below that the
                // PdfBox fallback opens the locked file with it
                // (the spike-proven road) — there is no longer a
                // version where a password could not be honoured.
                // A wrong password returns to this same state, so
                // the prompt can be retried or abandoned.
                Text(
                    "This PDF asks for a password.",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Enter it to read — it is used for this session " +
                        "only and never saved."
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { passwordOpen = true }) { Text("Enter password") }
                TextButton(onClick = onClose) { Text("Back") }
            }
        }

        is LocalReaderState.Failed -> StateScaffold(
            title = "Couldn't open",
            snackbar = snackbar,
            onClose = onClose
        ) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(current.message)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose) { Text("Back") }
            }
        }
    }

    if (passwordOpen) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passwordOpen = false },
            title = { Text("PDF password") },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    passwordOpen = false
                    viewModel.retryWithPassword(password)
                }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { passwordOpen = false }) { Text("Cancel") }
            }
        )
    }
}

/** The plain chrome for the dispatcher's own (pre-open) states —
 * a shared shell primitive only; the two readers build their own
 * chrome and never use this. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateScaffold(
    title: String,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

/** The read-aloud chrome button — a trivial shared primitive the
 * two readers both place in their own top bars; the TTS behaviour
 * itself lives in the ViewModel. */
@Composable
internal fun ReadAloudButton(viewModel: LocalDocReaderViewModel) {
    val tts by viewModel.ttsState.collectAsStateWithLifecycle()
    if (tts.playing) {
        IconButton(onClick = { viewModel.toggleReadAloud() }) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop reading aloud")
        }
    } else {
        IconButton(onClick = { viewModel.toggleReadAloud() }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Read aloud")
        }
    }
}

@Composable
private fun FileMissingBody(
    viewModel: LocalDocReaderViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: "book"
        viewModel.repointPicked(
            displayName = name,
            documentUri = uri.toString(),
            openStream = { context.contentResolver.openInputStream(uri) }
        )
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "File not found",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This book reads straight from its file, and the file " +
                "isn't where it was — it may have been moved or " +
                "deleted outside the app. Your place and bookmarks " +
                "are kept: point at the file again to carry on reading."
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            picker.launch(arrayOf("application/epub+zip", "application/pdf", "*/*"))
        }) { Text("Find the file") }
        TextButton(onClick = onClose) { Text("Back") }
    }
}
