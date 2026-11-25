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
import app.xreader.di.AppContainer

/**
 * v1.5.7 (Scopes B+C): the local-document reader's DISPATCHER.
 * This entry point stays exactly where the shell routes to it —
 * no route or shell changes — but it no longer renders a format
 * itself. Once a document is open it hands off by format:
 *
 *  - EPUB → the CORE reader (v1.7 OE-2): every EPUB converts
 *    through this destination's prepare host (the ViewModel's
 *    Loading/Refused/Failed states below) and redirects to
 *    the core route; no EPUB is rendered here any more.
 *  - PDF → the CORE reader (v1.7 OE-1): a local PDF opens in
 *    the one reader now; the route never sends one here.
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
    onOpenBookPage: (() -> Unit)? = null,
    // v1.7 Patch 3 (Item 1a): a prepared EPUB leaves for the
    // core route — the shell navigates to
    // "reader/<bookId>/<chapterIndex>", popping this
    // destination. Defaulted to a no-op so other callers keep
    // compiling; the shell always supplies it.
    onOpenCoreReader: (chapterIndex: Int) -> Unit = {}
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
    LaunchedEffect(viewModel) {
        viewModel.coreRedirect.collect { chapterIndex ->
            onOpenCoreReader(chapterIndex)
        }
    }

    var passwordOpen by remember { mutableStateOf(false) }

    when (val current = state) {
        is LocalReaderState.Ready -> when (current.session.format) {
            // v1.7 OE-2 (R29): the EPUB dispatch is deleted —
            // a Ready state carrying an EPUB session is
            // unreachable (EPUBs leave through the prepare
            // host's redirect before any session exists).
            // v1.7 OE-1 (R2): PDFs no longer arrive here
            // either — the route sends a local PDF to the
            // core reader. Anything reaching this branch
            // falls to the honest else below, exactly like
            // the other formats this surface does not render.
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
            // Code 127 (audit D4): the dressed LoadingState
            // (it branches per face and names the phase —
            // v1.5.8 F4's law), not a bare spinner over a
            // phase line.
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                app.xreader.ui.common.LoadingState(
                    whatIsHappening = current.phase,
                    modifier = Modifier.padding(24.dp)
                )
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
                // v1.7 MR-1 (A4): a converted-format book that
                // landed nothing gets the import resume as a
                // real retry. v1.7 OE-2 (R13): an EPUB whose
                // prepare failed carries the same flag — its
                // Try again re-runs the prepare. Every other
                // failure keeps the Back-only shape below.
                if (current.canResumeImport) {
                    Button(onClick = { viewModel.resumeImport() }) {
                        Text("Try again")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onClose) { Text("Back") }
                } else {
                    Button(onClick = onClose) { Text("Back") }
                }
            }
        }
    }

    if (passwordOpen) {
        var password by remember { mutableStateOf("") }
        // Code 127 (audit C14) note: shared by both faces'
        // local PDF readers — stock field + TextButton
        // actions under the active theme root is both faces'
        // dressed prompt anatomy (its online-reader twin at
        // ReaderScreen carries the same shape); no
        // face-specific control lives inside to branch.
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
        // Code 127 (audit D4): the face-branched toast host
        // (capsule in Nocturne, stock in the Material 3 face),
        // not a second stock host.
        snackbarHost = {
            SnackbarHost(
                snackbar,
                snackbar = { data ->
                    app.xreader.ui.common.DesignSnackbar(data)
                }
            )
        },
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
