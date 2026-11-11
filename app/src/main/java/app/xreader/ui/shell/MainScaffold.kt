package app.xreader.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.glance.appwidget.updateAll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.xreader.core.motion.fadeTween
import app.xreader.core.motion.pressBounce
import app.xreader.core.motion.stackEnter
import app.xreader.core.motion.stackExit
import app.xreader.core.motion.stackPopEnter
import app.xreader.core.motion.stackPopExit
import app.xreader.core.motion.tabEnter
import app.xreader.core.motion.tabExit
import app.xreader.core.motion.specFor
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.data.db.Book
import app.xreader.data.extension.player.PlayerChallengeBridge
import app.xreader.data.prefs.AppearanceState
import app.xreader.di.AppContainer
import app.xreader.ui.book.BookDetailScreen
import app.xreader.ui.book.BookDetailViewModel
import app.xreader.ui.common.XreaderPrimaryButton
import app.xreader.ui.downloads.BookDownloads
import app.xreader.ui.downloads.DownloadsScreen
import app.xreader.ui.downloads.DownloadsViewModel
import app.xreader.ui.library.LibraryScreen
import app.xreader.ui.library.LibraryViewModel
import app.xreader.ui.more.AboutScreen
import app.xreader.ui.more.LabsScreen
import app.xreader.ui.more.MoreScreen
import app.xreader.ui.opds.BrowseScreen
import app.xreader.ui.opds.OpdsCatalogScreen
import app.xreader.ui.opds.OpdsViewModel
import app.xreader.ui.reader.ReaderScreen
import app.xreader.ui.reader.ReaderViewModel
import app.xreader.ui.sources.AddFromLinkScreen
import app.xreader.ui.sources.GlobalSearchViewModel
import app.xreader.ui.sources.ExtensionDetailScreen
import app.xreader.ui.sources.ExtensionDetailViewModel
import app.xreader.ui.sources.LinkAddViewModel
import app.xreader.ui.sources.LoginMode
import app.xreader.ui.sources.LoginScreen
import app.xreader.ui.sources.ProviderPackViewModel
import app.xreader.ui.sources.SourceCatalogScreen
import app.xreader.ui.sources.SourceCatalogViewModel
import app.xreader.ui.sources.SourcesScreen
import app.xreader.ui.sources.SourcesViewModel
import app.xreader.ui.common.frostedGlass
import app.xreader.ui.theme.DeepSpace
import app.xreader.ui.theme.ElectricPurple
import app.xreader.ui.theme.LocalGlassSettings
import app.xreader.ui.theme.LocalHazeState
import app.xreader.ui.theme.NeonPurple
import app.xreader.ui.theme.VibrantCyan
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import dev.chrisbanes.haze.hazeSource
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The app shell (PLAN §3): four LABELLED tabs opening on Library.
 *
 * Phone: a floating frosted bar of FIXED height (64 dp) in the Scaffold
 * bottomBar slot. Tablet (≥ 840 dp wide): the bar becomes the left rail
 * (DESIGN.md large-screen rules). In both, the selection pill's rect
 * comes ONLY from [NavGeometry] — a pure, clamped function — and the
 * animated position is re-clamped every frame (the ReTranslate v1.1.1
 * invariant; nothing here sizes from raw layout constraints).
 */
/**
 * The five labelled tabs (v1.0.2, R08 — Line-up 2, locked by the user):
 * Library · Browse · History · Downloads · Me. "More" dissolved into
 * Me; History is a first-class tab. Order, routes and labels are
 * pinned by ShellNavTest.
 *
 * v1.1.0 (2026-10-31): the user renamed two LABELS to match the
 * Nocturne Violet design (design Q4) — Browse is now labelled
 * "Explore" and Me is now labelled "Profile". Labels only: the
 * routes ("browse", "me"), icons, enum entries and slot order are
 * unchanged, and ShellNavTest's pin was updated in the same change.
 */
enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Library("library", "Library", Icons.Filled.MenuBook),
    Browse("browse", "Explore", Icons.Filled.Explore),
    History("history", "History", Icons.Filled.History),
    Downloads("downloads", "Downloads", Icons.Filled.Download),
    Me("me", "Profile", Icons.Filled.Person)
}

internal val BarHeight = 64.dp
private val RailWidth = 104.dp
private val RailSlotHeight = 76.dp

@Composable
fun MainScaffold(
    container: AppContainer,
    appearance: AppearanceState,
    // v1.2.0 (WS6): the launch intro's ready signal — fired once,
    // the first time the Library's state leaves `loading` (its
    // first real content). Default no-op keeps other callers as-is.
    onLibraryReady: () -> Unit = {}
) {
    val navController = rememberNavController()
    // v1.5.1 (item 7): the content-navigation guard. Book and
    // reader routes were navigated bare — a double-tap (or a
    // tap landing while the enter transition still ran) could
    // push the same destination twice and stack/wedge the
    // transition (the Asura catalogue freeze: catalogue and
    // Book Details composited, input dead). Content routes
    // now go through this one door: singleTop, and an
    // identical route re-issued inside the debounce window
    // is ignored. Bottom tabs keep their own navigateToTab.
    var lastContentNavRoute by remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    var lastContentNavAt by remember {
        androidx.compose.runtime.mutableStateOf(0L)
    }
    val navigateContent: (String) -> Unit = { route ->
        val now = android.os.SystemClock.uptimeMillis()
        val duplicate = route == lastContentNavRoute &&
            now - lastContentNavAt < 900L
        if (!duplicate) {
            lastContentNavRoute = route
            lastContentNavAt = now
            navController.navigate(route) { launchSingleTop = true }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val onPhaseNote: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    // v1.5.6 (§2): the ONE reader-route resolver every
    // chapter-open entry point in this shell consults.
    val routeResolver = remember(container) { readerRouteResolverFor(container) }

    // Phase 2 view models live at host level so their message flows
    // can feed the one snackbar.
    val libraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel<LibraryViewModel>(
        factory = LibraryViewModel.factory(container)
    )
    val downloadsViewModel = androidx.lifecycle.viewmodel.compose.viewModel<DownloadsViewModel>(
        factory = DownloadsViewModel.factory(container)
    )
    val opdsViewModel = androidx.lifecycle.viewmodel.compose.viewModel<OpdsViewModel>(
        factory = OpdsViewModel.factory(container)
    )
    // Phase 3: sources live at host level too (the login handoff and
    // its session-saved events are shared across destinations).
    val sourcesViewModel = androidx.lifecycle.viewmodel.compose.viewModel<SourcesViewModel>(
        factory = SourcesViewModel.factory(container)
    )
    // K2 (Keiyoushi player): a Cloudflare prompt from extension
    // traffic opens the sanctioned screen on the existing login
    // route (SITE_CHECK — a check to finish, not an account
    // sign-in), at the challenged URL itself. Each prompt is
    // handled once; when the login request clears (saved or
    // dismissed) the bridge releases its waiter, which takes the
    // verdict from the session store.
    val playerPrompt by container.playerChallengeBridge.prompts
        .collectAsStateWithLifecycle()
    var handledPlayerPrompt by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<PlayerChallengeBridge.Prompt?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(playerPrompt) {
        val prompt = playerPrompt
        if (prompt == null) {
            handledPlayerPrompt = null
        } else if (prompt != handledPlayerPrompt) {
            handledPlayerPrompt = prompt
            sourcesViewModel.startLogin(
                sessionKey = prompt.sessionKey,
                url = "https://${prompt.host}",
                title = prompt.host,
                mode = LoginMode.SITE_CHECK,
                targetUrl = prompt.url
            )
            navController.navigate("login")
        }
    }
    val shellLoginRequest by sourcesViewModel.loginRequest
        .collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(shellLoginRequest) {
        if (shellLoginRequest == null) {
            container.playerChallengeBridge.dismissCurrent()
        }
    }
    // v1.3.5 (Cycle 1): the provider-pack card's view model
    // (Repositories screen).
    val packViewModel = androidx.lifecycle.viewmodel.compose.viewModel<ProviderPackViewModel>(
        factory = ProviderPackViewModel.factory(container)
    )
    val linkAddViewModel = androidx.lifecycle.viewmodel.compose.viewModel<LinkAddViewModel>(
        factory = LinkAddViewModel.factory(container)
    )
    // v1.3.5 (Cycle 1): the in-app updater — one VM for the
    // floating card, What's New and the Updates & about
    // section, so a download's progress shows everywhere.
    val appUpdateViewModel = androidx.lifecycle.viewmodel.compose
        .viewModel<app.xreader.ui.update.AppUpdateViewModel>(
            factory = app.xreader.ui.update.AppUpdateViewModel.factory(container)
        )
    androidx.compose.runtime.LaunchedEffect(appUpdateViewModel) {
        appUpdateViewModel.onAppOpen()
    }
    // The updater's intents (system installer, the install-
    // permission setting) are fired here — the VM never
    // starts activities itself.
    androidx.compose.runtime.LaunchedEffect(appUpdateViewModel) {
        appUpdateViewModel.intents.collect { intent ->
            runCatching { context.startActivity(intent) }
        }
    }
    // v1.3.2: the all-sources search VM and the session hand-off
    // live in XreaderNavHost below, with the destinations that
    // use them.
    androidx.compose.runtime.LaunchedEffect(libraryViewModel) {
        libraryViewModel.messages.collect { msg -> snackbar.showSnackbar(msg) }
    }
    // v1.2.0 (WS6): the Library-ready signal for the launch intro —
    // the existing LibraryViewModel `loading` flag is the shell's
    // first-real-content marker (it clears when the shelf combine
    // first emits, books or honest empty state alike). Suspends
    // until then, fires once, and the effect ends.
    androidx.compose.runtime.LaunchedEffect(libraryViewModel) {
        libraryViewModel.state.first { !it.loading }
        onLibraryReady()
    }
    androidx.compose.runtime.LaunchedEffect(opdsViewModel) {
        opdsViewModel.messages.collect { msg -> snackbar.showSnackbar(msg) }
    }
    androidx.compose.runtime.LaunchedEffect(sourcesViewModel) {
        sourcesViewModel.messages.collect { msg -> snackbar.showSnackbar(msg) }
    }
    androidx.compose.runtime.LaunchedEffect(linkAddViewModel) {
        linkAddViewModel.messages.collect { msg -> snackbar.showSnackbar(msg) }
    }
    // v1.5.2 fix (FIX-2, §1): promotion is reconciled on app
    // foreground / surface resume — a solve that completed with
    // zero collectors still promotes at this evaluation point.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    runCatching { container.evaluatePendingOperations() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Phase 6: opening the app settles EXP (idempotent) and honours
    // a home-screen widget tap that asked for a specific book.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        scope.launch {
            // v1.3.0: the built-in default sources install (or
            // safely refresh) before anything else looks at the
            // source list — offline, idempotent, and it never
            // touches a row the user has changed (RepoManager).
            runCatching { container.repoManager.installBundledDefaults() }
            // Manga programme (M1): the Keiyoushi repository
            // row is pre-registered (idempotent, offline — the
            // row insert only; its site list loads on demand
            // through the foreign-repo reconstruction).
            runCatching { container.repoManager.registerKeiyoushiRepo() }
            // Keiyoushi player (K3): restore the installed
            // manga extensions — records reload, every file
            // is re-hashed against its pin, and consented
            // packages re-load (sequentially, on this
            // background scope, so app open isn't slowed).
            runCatching {
                container.keiyoushiPlayerManager.restoreInstalled()
            }
            // v1.5.2 (§8.5 Phase 0 — offline, local, bounded):
            // favourites re-key, body demotion, History seeding,
            // fossil-name repair, per-provider journal.
            val phase0 = runCatching { container.updatePass152.runPhase0() }
                .getOrNull()
            // v1.5.2 fix (FIX-2, §1): process-start evaluation —
            // RUNNING records return to READY and may be claimed
            // again; terminal traces prune after 7 days.
            runCatching { container.evaluatePendingOperations(processStart = true) }
            // v1.5.2 fix (FIX-10, §8.5): the DEFERRED phase —
            // post-interactive, per-provider, journaled: link-added
            // re-derivation, cover backfill, rating index leg. On
            // completion Explore shows the one-time summary once.
            runCatching {
                val deferred = container.updatePass152.runDeferred()
                if (deferred.completed && !deferred.skippedOffline) {
                    container.updatePassSummary.value = deferred.summaryLine(
                        refreshedTotal = deferred.rederived +
                            (phase0?.refreshed ?: 0),
                        renamedTotal = phase0?.renamed ?: 0
                    )
                }
            }
            // v1.5.2 (§8.4): rating backfill for pre-stamp records.
            runCatching {
                container.keiyoushiPlayerManager.backfillRatings()
            }
            // v1.3.5 (Cycle 1): the provider-pack channel's
            // throttled automatic check — at most once a day,
            // silent on failure, and it can only ever touch
            // pack-managed sources (ProviderPackManager).
            runCatching {
                container.providerPackManager.checkForUpdates(manual = false)
            }
            // v1.5.39 (item 4): the Keiyoushi manga-source
            // check rides beside it — same once-a-day
            // throttle (its own SourcePrefs stamp), silent
            // on failure; newly flagged updates notify once
            // per set, and the persisted flags drive the
            // Explore badge and banner (AppContainer).
            runCatching {
                container.checkKeiyoushiSourceUpdates()
            }
            // v1.3.1 (item 52): one source per canonical domain
            // — the built-in survives, older duplicates are
            // removed and their books rebound (SourceDedupe).
            // Removals are audit-logged, never silent.
            runCatching { container.sourceDedupe.runOnce() }
                .getOrNull()?.forEach { removed ->
                    android.util.Log.i("XreaderDedupe", removed.logLine())
                }
            runCatching { container.expService.settle() }
            // Phase 6: keep the home-screen widget honest.
            runCatching {
                app.xreader.widget.XreaderWidget()
                    .updateAll(context)
            }
            // v1.5.34 (item 1d): the per-version sweeps (heal,
            // stored-book repair, source migration) no longer
            // ride this sequential startup chain — they run on
            // their own deferred effect below, after first frame,
            // so a heavy install day's first launch does no
            // sweep network work inside the shell's opening
            // composition chain.
        }
    }
    // v1.5.34 (item 1d): the per-version startup sweeps,
    // deferred until after the first frame plus a settle beat
    // (the Library is up and idle before any sweep network work
    // starts). Discipline unchanged from where they lived in
    // the startup chain: heal + stored-book repair run under
    // asBackgroundWork (their book-sync paths take the
    // details-only gate — no foreground chapter walks, the
    // v1.5.26 toast lever); the source migration keeps its
    // documented exception (its re-derivation is owed the
    // learner's full escalation).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        kotlinx.coroutines.delay(2_500)
        // v1.0.5 (E1): the heal-my-library sweep — once per
        // app version, link sources are re-driven with the
        // honest engine and the failures repaired quietly in
        // the background. It gates itself on network, metering
        // and battery; a skipped run retries on a later launch.
        runCatching {
            val installedCode = runCatching {
                @Suppress("DEPRECATION")
                container.applicationContext.packageManager.getPackageInfo(
                    container.applicationContext.packageName, 0
                ).versionCode
            }.getOrDefault(0)
            if (installedCode > 0) {
                // v1.0.6 (W5.2): the sweep is background work —
                // its probes never render.
                container.renderRuntime.asBackgroundWork {
                    container.healSweep.runIfNeeded(installedCode)
                }
                // v1.3.3 (scope S6): the stored-book repair
                // sweep — once per version, Library books
                // whose stored chapter lists/covers predate
                // the canonical merge are re-synced against
                // their sources (the 3,253-row store heals
                // to the source's own 3,229). Background
                // work likewise: its fetches never render.
                container.renderRuntime.asBackgroundWork {
                    container.storedBookRepair
                        .runUpgradeSweep(installedCode)
                }
                // v1.5.1 (item 1): the update-time source
                // migration — learned sources re-derived
                // with the current learner, duplicates of
                // shipped sites merged away (the user's
                // no-duplicates rule). NOT background work:
                // the re-derivation is owed the learner's
                // full escalation (the API road renders and
                // records), and it runs once per version,
                // never blocking first paint (now deferred past
                // it entirely). Failure-isolated per source
                // inside; a failure here changes nothing.
                runCatching {
                    container.sourceUpdateMigration
                        .runIfNeeded(installedCode)
                }.getOrNull()?.forEach { line ->
                    android.util.Log.i("XreaderMigrate", line)
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.pendingOpenBook.collect { bookId ->
            if (bookId != null) {
                container.pendingOpenBook.value = null
                val progress = runCatching {
                    container.database.readingProgressDao().getForBook(bookId)
                }.getOrNull()
                // v1.5.6 (§2.2 entry point 5): the programmatic
                // open bypasses every screen, so it consults the
                // resolver like everything else — a manga book
                // deep-linked here opens Komikku directly.
                openReaderViaResolver(
                    routeResolver, bookId,
                    progress?.chapterIndex ?: 0, context, navigateContent
                )
            }
        }
    }
    // v1.0.9: a pinned Live Source shortcut opens that source's
    // catalogue in this window.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.pendingOpenSource.collect { target ->
            if (target != null) {
                container.pendingOpenSource.value = null
                navigateContent("source/${target.first}/${target.second}")
            }
        }
    }
    // v1.5.39 (item 4): a source-update notification tap
    // opens the manga shelf (the Keiyoushi repo's own route)
    // — the same shelf Explore's banner and Manga sources
    // door lead to.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.pendingOpenSourceShelf.collect { open ->
            if (open) {
                container.pendingOpenSourceShelf.value = false
                val shelfRepo = sourcesViewModel.state.value.repos
                    .firstOrNull { repo ->
                        app.xreader.data.extension.KeiyoushiIndex
                            .isKeiyoushiRepo(repo.name, repo.indexUrl)
                    }
                if (shelfRepo != null) {
                    navController.navigate("repo/${shelfRepo.id}")
                }
            }
        }
    }

    // Import picker (SAF): multiple documents, any type — the sniffer
    // decides what each file really is.
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val names = uris.map { uri -> displayName(context, uri) }
        libraryViewModel.stagePickedUris(uris, names) { uri ->
            runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        }
    }
    val onAddBook: () -> Unit = {
        importLauncher.launch(arrayOf("*/*"))
    }

    // Export: "Make EPUB"/"Export" create a document via SAF, then the
    // exporter writes into the chosen stream.
    var pendingExport by remember { mutableStateOf<Pair<Book, String>?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri != null && pending != null) {
            scope.launch {
                val (book, kind) = pending
                val stream = runCatching {
                    context.contentResolver.openOutputStream(uri)
                }.getOrNull()
                if (stream == null) {
                    snackbar.showSnackbar("That place couldn't be written to.")
                    return@launch
                }
                val result = stream.use {
                    if (kind == "cbz") {
                        container.bookExporter.exportCbz(book, it)
                    } else {
                        container.bookExporter.exportEpub(book, it)
                    }
                }
                snackbar.showSnackbar(
                    when (result) {
                        is app.xreader.core.error.AppResult.Ok ->
                            "Saved “${book.title}” as ${kind.uppercase()}"
                        is app.xreader.core.error.AppResult.Err ->
                            app.xreader.core.error.ErrorText.displayText(result.error)
                    }
                )
            }
        }
    }

    // v1.0.1 customisation: library/navigation preferences drive
    // the shell (bar style, tab labels) and the start screen.
    val libraryState by container.libraryPrefs.state.collectAsStateWithLifecycle(
        initialValue = app.xreader.data.library.LibraryState()
    )
    var startHandled by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(libraryState.startScreen) {
        if (startHandled) return@LaunchedEffect
        startHandled = true
        when (libraryState.startScreen) {
            app.xreader.data.library.StartScreen.DOWNLOADS ->
                navigateToTab(navController, Tab.Downloads)
            app.xreader.data.library.StartScreen.CONTINUE_READING -> {
                val recent = runCatching {
                    container.database.readingProgressDao().allProgress()
                        .maxByOrNull { it.updatedAt }
                }.getOrNull()
                if (recent != null) {
                    // v1.5.6 (§2.2 entry point 4): app-start
                    // Continue Reading goes through the resolver.
                    openReaderViaResolver(
                        routeResolver, recent.bookId, recent.chapterIndex,
                        context, navigateContent
                    )
                }
            }
            app.xreader.data.library.StartScreen.LIBRARY -> Unit
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Read at this scope: inside the nested Box the constraints
        // receiver is no longer implicit under Kotlin 2.2.
        val isWideLayout = maxWidth >= 840.dp
        Box(Modifier.fillMaxSize()) {
        if (isWideLayout) {
            RailShell(
                navController, snackbar, appearance, container, onPhaseNote,
                libraryViewModel, downloadsViewModel, opdsViewModel,
                sourcesViewModel, linkAddViewModel, onAddBook,
                onExport = { book, kind -> pendingExport = book to kind },
                libraryState = libraryState,
                appUpdateViewModel = appUpdateViewModel,
                packViewModel = packViewModel
            )
        } else {
            PhoneShell(
                navController, snackbar, appearance, container, onPhaseNote,
                libraryViewModel, downloadsViewModel, opdsViewModel,
                sourcesViewModel, linkAddViewModel, onAddBook,
                onExport = { book, kind -> pendingExport = book to kind },
                libraryState = libraryState,
                appUpdateViewModel = appUpdateViewModel,
                packViewModel = packViewModel
            )
        }
        // v1.3.5: the floating update card, over the Library
        // only, above the dock — at most once a day, and it
        // never blocks reading.
        UpdateCardOverlay(
            navController = navController,
            viewModel = appUpdateViewModel,
            onReadMore = { navController.navigate("whatsNew") }
        )
        }
    }
}

/**
 * v1.3.5: the floating update card's host. It floats over
 * the Library (the app's home) above the navigation dock,
 * appears only while the updater has a card to present —
 * the ViewModel enforces the once-a-day rule — and floats
 * over the content without ever blocking it: the Library
 * stays fully usable around and under it.
 */
@Composable
private fun UpdateCardOverlay(
    navController: NavHostController,
    viewModel: app.xreader.ui.update.AppUpdateViewModel,
    onReadMore: () -> Unit
) {
    val backStack by navController.currentBackStackEntryAsState()
    val onLibrary = backStack?.destination?.route == Tab.Library.route
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.compose.animation.AnimatedVisibility(
        visible = onLibrary && state.cardRelease != null,
        enter = fadeIn(fadeTween<Float>()),
        exit = fadeOut(fadeTween<Float>()),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                // Above the floating dock (64dp) + its margin,
                // and clear of the phone's dead bottom strip.
                .padding(bottom = 108.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            app.xreader.ui.update.UpdateCard(
                state = state,
                onUpdateNow = { viewModel.startDownload() },
                onIgnore = { viewModel.ignoreCard() },
                onReadMore = onReadMore,
                onInstall = { viewModel.install() },
                onCancelDownload = { viewModel.cancelDownload() },
                onOpenInstallSettings = { viewModel.openInstallPermissionSettings() }
            )
        }
    }
}

/** SAF display name for a picked document (falls back to the URI tail). */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String {
    var name: String? = null
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
            }
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
}

/** True while an immersive route (reader, login) is showing: the
 * shell chrome animates away and the destination owns the screen. */
@Composable
private fun shellImmersive(navController: NavHostController): Boolean {
    val entry by navController.currentBackStackEntryAsState()
    return ShellChrome.isImmersiveRoutePattern(entry?.destination?.route)
}

/**
 * v1.0.4 (D1/D7/D9): the shell's backdrop stage — and the app's ONE
 * haze source. A full-size layer behind the content: under FROSTED
 * it is the user's mesh (NeonPurple glow over DeepSpace in dark
 * mode, a faint violet glow over the light surface), and every
 * glass surface — cards, nav bar, rail — is a haze EFFECT sampling
 * it. Keeping the source here, as a sibling behind the NavHost
 * instead of a modifier ON the NavHost, is the topology change
 * that lets content cards blur safely (an effect node inside the
 * source's subtree was Haze's second R01 IAE). Under TINTED/SOLID
 * nothing changes visually: the base is the plain scheme
 * background and no effect nodes exist. Pure black (true black /
 * AMOLED schemes) keeps its promise — no glow, black base.
 */
@Composable
private fun MeshBackdrop(
    modifier: Modifier = Modifier,
    bloom: Boolean = false,
    backgroundFile: File? = null
) {
    val glass = LocalGlassSettings.current
    val haze = LocalHazeState.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val pureBlack = dark && scheme.background == Color.Black
    val base = if (dark && glass.frosted && !pureBlack) {
        DeepSpace
    } else {
        scheme.background
    }
    Box(
        modifier
            .background(base)
            .drawBehind {
                // v1.0.7 (AnymeX port): BLOOM — with bloom on, the
                // glows burn in the ACTIVE SCHEME's own primary /
                // tertiary and also appear under tinted/solid
                // glass (weaker). With bloom off this block is the
                // original v1.0.4 mesh, byte for byte in effect.
                if ((glass.frosted || bloom) && !pureBlack) {
                    val glow = when {
                        bloom && dark -> scheme.primary.copy(alpha = 0.40f)
                        dark -> NeonPurple.copy(alpha = 0.40f)
                        bloom -> scheme.primary.copy(alpha = 0.14f)
                        else -> ElectricPurple.copy(alpha = 0.10f)
                    }
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(glow, Color.Transparent),
                            center = Offset(size.width * 0.5f, size.height * 0.28f),
                            radius = size.maxDimension * 0.95f
                        )
                    )
                    val glow2 = when {
                        bloom && dark -> scheme.tertiary.copy(alpha = 0.12f)
                        dark -> VibrantCyan.copy(alpha = 0.10f)
                        bloom -> scheme.tertiary.copy(alpha = 0.07f)
                        else -> ElectricPurple.copy(alpha = 0.05f)
                    }
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(glow2, Color.Transparent),
                            center = Offset(size.width, size.height),
                            radius = size.maxDimension * 0.7f
                        )
                    )
                }
            }
            // FIX-23: the haze source is applied ONLY when the
            // FROSTED style is actually in effect. On both plain
            // paths — the automatic below-API-31 resolution and
            // the user's safe-appearance (TINTED) mode — no Haze
            // node enters the composition at all (glass-free by
            // construction); the effect sites in frostedGlass
            // are gated on the same flag.
            .let { if (haze != null && glass.frosted) it.hazeSource(haze) else it }
    ) {
        // v1.0.7 Liquid background: the user's own picture fills
        // the stage (the glass above still samples it through
        // the haze source — it lives in the same subtree), under
        // a scheme-coloured scrim that keeps text honest.
        if (backgroundFile != null && !pureBlack) {
            coil.compose.AsyncImage(
                model = backgroundFile,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(base.copy(alpha = 0.62f))
            )
        }
    }
}

/**
 * v1.0.7 (AnymeX port): the film-grain overlay — one fixed-seed
 * noise tile, drawn tiled at the user's strength. Deliberately
 * dumb: a static Canvas, no animation, no pointer input; the
 * shells only mount it when the grain is on, the performance
 * tier allows it, and the route is not immersive (reading
 * pages never get grain).
 */
@Composable
private fun GrainOverlay(intensity: Float, modifier: Modifier = Modifier) {
    val tile = remember {
        val size = 144
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val pixels = IntArray(size * size)
        val random = kotlin.random.Random(20261030)
        for (i in pixels.indices) {
            val v = random.nextInt(256)
            pixels[i] = android.graphics.Color.rgb(v, v, v)
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.asImageBitmap()
    }
    androidx.compose.foundation.Canvas(modifier.fillMaxSize()) {
        val step = 144f
        var x = 0f
        while (x < size.width) {
            var y = 0f
            while (y < size.height) {
                drawImage(tile, topLeft = Offset(x, y), alpha = intensity)
                y += step
            }
            x += step
        }
    }
}

@Composable
private fun PhoneShell(
    navController: NavHostController,
    snackbar: SnackbarHostState,
    appearance: AppearanceState,
    container: AppContainer,
    onPhaseNote: (String) -> Unit,
    libraryViewModel: LibraryViewModel,
    downloadsViewModel: DownloadsViewModel,
    opdsViewModel: OpdsViewModel,
    sourcesViewModel: SourcesViewModel,
    linkAddViewModel: LinkAddViewModel,
    onAddBook: () -> Unit,
    onExport: (Book, String) -> Unit,
    libraryState: app.xreader.data.library.LibraryState =
        app.xreader.data.library.LibraryState(),
    appUpdateViewModel: app.xreader.ui.update.AppUpdateViewModel,
    packViewModel: app.xreader.ui.sources.ProviderPackViewModel
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // v1.0.1: immersive routes (reader, login) hide the bar —
            // it animates out with the motion engine, never pops.
            AnimatedVisibility(
                visible = !shellImmersive(navController),
                enter = fadeIn(fadeTween<Float>()),
                exit = fadeOut(fadeTween<Float>())
            ) {
                // v1.5.39 (item 4): the Explore tab carries
                // the pending manga-source update count as a
                // numeric badge (donor pattern).
                val sourcesState by sourcesViewModel.state
                    .collectAsStateWithLifecycle()
                FloatingNavBar(
                    selected = selectedTab(navController),
                    reduceMotion = appearance.reduceMotion,
                    onSelect = { tab -> navigateToTab(navController, tab) },
                    barStyle = libraryState.navBarStyle,
                    labelStyle = libraryState.tabLabelStyle,
                    exploreBadgeCount =
                        sourcesState.keiyoushiPendingUpdateCount
                )
            }
        }
    ) { padding ->
        // v1.0.4 (D1): the mesh backdrop fills the whole scaffold
        // (behind the bottom bar too, so the nav's blur has the
        // stage to sample); the NavHost sits above it, padded.
        // v1.0.7: the backdrop also carries bloom + the liquid
        // background; grain floats over the content (never over
        // immersive routes, never on the MINIMAL tier / SMOOTH).
        val backdropFile = appearance.backgroundImage?.let { name ->
            File(container.bookStorage.filesDir, "backgrounds/$name")
        }
        val grainOn = appearance.grain && !shellImmersive(navController) &&
            appearance.performanceMode !=
                app.xreader.data.prefs.PerformanceMode.SMOOTH &&
            app.xreader.data.prefs.PerformancePolicy.tierForRam(
                container.totalRamMb()
            ) != app.xreader.data.prefs.PerfTier.MINIMAL
        Box(Modifier.fillMaxSize()) {
            MeshBackdrop(
                Modifier.fillMaxSize(),
                bloom = appearance.bloom,
                backgroundFile = backdropFile
            )
            XreaderNavHost(
                navController, container, Modifier.fillMaxSize().padding(padding),
                appearance.reduceMotion, onPhaseNote,
                libraryViewModel, downloadsViewModel, opdsViewModel,
                sourcesViewModel, linkAddViewModel, onAddBook, onExport,
                appUpdateViewModel = appUpdateViewModel,
                packViewModel = packViewModel
            )
            if (grainOn) {
                GrainOverlay(appearance.grainIntensity, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun RailShell(
    navController: NavHostController,
    snackbar: SnackbarHostState,
    appearance: AppearanceState,
    container: AppContainer,
    onPhaseNote: (String) -> Unit,
    libraryViewModel: LibraryViewModel,
    downloadsViewModel: DownloadsViewModel,
    opdsViewModel: OpdsViewModel,
    sourcesViewModel: SourcesViewModel,
    linkAddViewModel: LinkAddViewModel,
    onAddBook: () -> Unit,
    onExport: (Book, String) -> Unit,
    libraryState: app.xreader.data.library.LibraryState =
        app.xreader.data.library.LibraryState(),
    appUpdateViewModel: app.xreader.ui.update.AppUpdateViewModel,
    packViewModel: app.xreader.ui.sources.ProviderPackViewModel
) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        // v1.0.4 (D1): same backdrop stage as the phone shell.
        // v1.0.7: same bloom / background / grain treatment.
        val backdropFile = appearance.backgroundImage?.let { name ->
            File(container.bookStorage.filesDir, "backgrounds/$name")
        }
        val grainOn = appearance.grain && !shellImmersive(navController) &&
            appearance.performanceMode !=
                app.xreader.data.prefs.PerformanceMode.SMOOTH &&
            app.xreader.data.prefs.PerformancePolicy.tierForRam(
                container.totalRamMb()
            ) != app.xreader.data.prefs.PerfTier.MINIMAL
        Box(Modifier.fillMaxSize()) {
            MeshBackdrop(
                Modifier.fillMaxSize(),
                bloom = appearance.bloom,
                backgroundFile = backdropFile
            )
            Row(Modifier.fillMaxSize().padding(padding)) {
                AnimatedVisibility(
                    visible = !shellImmersive(navController),
                    enter = fadeIn(fadeTween<Float>()),
                    exit = fadeOut(fadeTween<Float>())
                ) {
                val railSourcesState by sourcesViewModel.state
                    .collectAsStateWithLifecycle()
                NavRail(
                    selected = selectedTab(navController),
                    reduceMotion = appearance.reduceMotion,
                    onSelect = { tab -> navigateToTab(navController, tab) },
                    onAddBook = onAddBook,
                    labelStyle = libraryState.tabLabelStyle,
                    exploreBadgeCount =
                        railSourcesState.keiyoushiPendingUpdateCount
                )
                }
                XreaderNavHost(
                    navController, container,
                    Modifier.weight(1f),
                    appearance.reduceMotion, onPhaseNote,
                    libraryViewModel, downloadsViewModel, opdsViewModel,
                    sourcesViewModel, linkAddViewModel, onAddBook, onExport,
                    appUpdateViewModel = appUpdateViewModel,
                    packViewModel = packViewModel
                )
            }
            if (grainOn) {
                GrainOverlay(appearance.grainIntensity, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun XreaderNavHost(
    navController: NavHostController,
    container: AppContainer,
    modifier: Modifier,
    reduceMotion: Boolean,
    onPhaseNote: (String) -> Unit,
    libraryViewModel: LibraryViewModel,
    downloadsViewModel: DownloadsViewModel,
    opdsViewModel: OpdsViewModel,
    sourcesViewModel: SourcesViewModel,
    linkAddViewModel: LinkAddViewModel,
    onAddBook: () -> Unit,
    onExport: (Book, String) -> Unit,
    appUpdateViewModel: app.xreader.ui.update.AppUpdateViewModel,
    packViewModel: app.xreader.ui.sources.ProviderPackViewModel
) {
    val scope = rememberCoroutineScope()
    // v1.5.1 (item 7): the same content-navigation guard as in
    // MainScaffold, scoped to this host (destinations live here):
    // singleTop + identical-route debounce, so a double-tap can
    // never stack a book/reader destination mid-transition.
    var lastContentNavRoute by remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    var lastContentNavAt by remember {
        androidx.compose.runtime.mutableStateOf(0L)
    }
    val navigateContent: (String) -> Unit = { route ->
        val now = android.os.SystemClock.uptimeMillis()
        val duplicate = route == lastContentNavRoute &&
            now - lastContentNavAt < 900L
        if (!duplicate) {
            lastContentNavRoute = route
            lastContentNavAt = now
            navController.navigate(route) { launchSingleTop = true }
        }
    }
    // v1.5.6 (§2): the ONE chapter-open door for every destination
    // in this host — book page, Library Continue Reading and
    // History all resolve through ReaderRouteResolver before any
    // reader opens (Komikku verdicts launch the Activity directly;
    // Downloads/Explore reach the reader only through the book
    // page, §2.2 entry points 6/7, so they inherit this door).
    val routeResolver = remember(container) { readerRouteResolverFor(container) }
    val hostContext = androidx.compose.ui.platform.LocalContext.current
    val openReader: (Long, Int) -> Unit = { bookId, chapterIndex ->
        scope.launch {
            openReaderViaResolver(
                routeResolver, bookId, chapterIndex, hostContext, navigateContent
            )
        }
    }
    // v1.3.2 (scope D): the all-sources search VM, created at
    // nav-host level (the destinations that use it live here).
    val globalSearchViewModel = androidx.lifecycle.viewmodel.compose
        .viewModel<GlobalSearchViewModel>(
            factory = GlobalSearchViewModel.factory(container)
        )
    androidx.compose.runtime.LaunchedEffect(globalSearchViewModel) {
        globalSearchViewModel.openedBook.collect { bookId ->
            navigateContent("book/$bookId")
        }
    }
    // v1.3.2 (scope A2): THE session hand-off, shared by every
    // surface (catalogue, book page, reader): open the sanctioned
    // one-time screen for a source — solve its check or sign in
    // on the site's own page — in the mode the failure itself
    // called for. The screen keeps only the session's cookies;
    // the app never sees a password.
    val openSessionHandoff: (
        Long, app.xreader.data.extension.SessionNeed.Kind, String?
    ) -> Unit = { extId, kind, targetUrl ->
        scope.launch {
            val ext = container.database
                .installedExtensionDao().getById(extId)
            val firstHost = ext?.let {
                app.xreader.data.extension.ExtensionPermissions
                    .parse(it.permissionsJson)
                    .domains.firstOrNull()
            }
            if (ext != null && firstHost != null) {
                sourcesViewModel.startLogin(
                    sessionKey = sourcesViewModel.sessionKeyFor(extId),
                    url = "https://$firstHost",
                    title = ext.displayName,
                    mode = when (kind) {
                        app.xreader.data.extension
                            .SessionNeed.Kind.SITE_CHECK ->
                            app.xreader.ui.sources.LoginMode.SITE_CHECK
                        app.xreader.data.extension
                            .SessionNeed.Kind.SIGN_IN ->
                            app.xreader.ui.sources.LoginMode.SIGN_IN
                    },
                    // v1.3.4 (U3): the failure's own page (e.g.
                    // the reader page where the check widget
                    // lives) when the caller named one.
                    targetUrl = targetUrl
                )
                navController.navigate("login")
            } else {
                onPhaseNote(
                    "This source doesn't declare a site to sign in to"
                )
            }
        }
    }
    val coverFile: (String?) -> File? = { path ->
        path?.let { container.bookStorage.resolve(it) }
    }
    val assetFile: (Long, String) -> File = { bookId, name ->
        // v1.5.7 (scope F smoothness): contentDir() mkdirs on
        // every call — a filesystem write on the composition
        // thread for a read-side path. contentFile resolves
        // the same path without creating anything.
        container.bookStorage.contentFile(bookId, "img-$name")
    }
    // Phase 5: one sync brain for the whole shell — the Sync screen
    // uses it, and opening the app gives auto-sync its chance (it is
    // quiet, off by default, and never blocks reading).
    val syncViewModel = androidx.lifecycle.viewmodel.compose
        .viewModel<app.xreader.ui.sync.SyncViewModel>(
            factory = app.xreader.ui.sync.SyncViewModel.factory(container)
        )
    androidx.compose.runtime.LaunchedEffect(syncViewModel) {
        syncViewModel.autoSyncIfDue()
    }
    // v1.0.4 (D8): the user's Apple stack geometry for PUSHED
    // routes (full slide in, quarter-offset parallax out/back);
    // tab-to-tab switches crossfade and never slide. The branch is
    // the engine's pure isTabSwitch over the two endpoint routes.
    val tabRoutes = remember { Tab.entries.map { it.route }.toSet() }
    NavHost(
        navController = navController,
        startDestination = Tab.Library.route,
        modifier = modifier,
        enterTransition = {
            if (SpringMotionEngine.isTabSwitch(
                    initialState.destination.route,
                    targetState.destination.route, tabRoutes
                )
            ) tabEnter() else stackEnter(reduceMotion)
        },
        exitTransition = {
            if (SpringMotionEngine.isTabSwitch(
                    initialState.destination.route,
                    targetState.destination.route, tabRoutes
                )
            ) tabExit() else stackExit(reduceMotion)
        },
        popEnterTransition = {
            if (SpringMotionEngine.isTabSwitch(
                    initialState.destination.route,
                    targetState.destination.route, tabRoutes
                )
            ) tabEnter() else stackPopEnter(reduceMotion)
        },
        popExitTransition = {
            if (SpringMotionEngine.isTabSwitch(
                    initialState.destination.route,
                    targetState.destination.route, tabRoutes
                )
            ) tabExit() else stackPopExit(reduceMotion)
        }
    ) {
        composable(Tab.Library.route) {
            val appearance by container.appearancePrefs.state
                .collectAsStateWithLifecycle(
                    initialValue = app.xreader.data.prefs.AppearanceState()
                )
            LibraryScreen(
                viewModel = libraryViewModel,
                coverFile = coverFile,
                reduceMotion = reduceMotion,
                onAddBook = onAddBook,
                onOpenBook = { bookId -> navigateContent("book/$bookId") },
                // v1.5.6 (§2.2 entry point 2): Library Continue
                // Reading resolves through the routing law.
                onContinueReading = { bookId, chapterIndex ->
                    openReader(bookId, chapterIndex)
                },
                onProfile = { navController.navigate("profile") },
                onOpenAddList = { navController.navigate("addList") },
                coverGlow = appearance.coverGlow &&
                    appearance.bookColours !=
                    app.xreader.data.prefs.BookColoursScope.BOOK_PAGE_ONLY
            )
        }
        composable("updates") {
            app.xreader.ui.library.UpdatesScreen(
                viewModel = libraryViewModel,
                coverFile = coverFile,
                onBack = { navController.popBackStack() },
                onOpenBook = { bookId -> navigateContent("book/$bookId") }
            )
        }
        composable("addList") {
            val addListViewModel: app.xreader.ui.library.AddListViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel(
                    factory = app.xreader.ui.library.AddListViewModel
                        .factory(container)
                )
            app.xreader.ui.library.AddListScreen(
                viewModel = addListViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Tab.Browse.route) { entry ->
            // v1.0.5 (C3): Browse is the neat list; OPDS servers
            // fold into it, adding and the store are pushed screens.
            val opdsServers by opdsViewModel.servers
                .collectAsStateWithLifecycle()
            // v1.3.2 (scope D): a "Find this title" from a book
            // page arrives as a preset on THIS entry — run it in
            // Explore's own search field, then clear it.
            androidx.compose.runtime.LaunchedEffect(entry) {
                entry.savedStateHandle
                    .getStateFlow<String?>("presetQuery", null)
                    .collect { preset ->
                        if (!preset.isNullOrBlank()) {
                            entry.savedStateHandle["presetQuery"] = null
                            globalSearchViewModel.setQuery(preset)
                            globalSearchViewModel.search()
                        }
                    }
            }
            SourcesScreen(
                viewModel = sourcesViewModel,
                searchViewModel = globalSearchViewModel,
                onOpenSource = { extId, sourceId ->
                    navigateContent("source/$extId/${android.net.Uri.encode(sourceId)}")
                },
                onOpenExtension = { extId -> navController.navigate("extensions/$extId") },
                onOpenOpdsServers = { navController.navigate("opdsServers") },
                onAddSource = { navController.navigate("addSource") },
                onOpenStore = { navController.navigate("sourceStore") },
                onOpenManageSources = { navController.navigate("manageSources") },
                // v1.5.39 (item 4): straight to the manga
                // shelf — the Keiyoushi repo's own route,
                // resolved by the same identity test the
                // store uses; the store is the fallback if
                // the row somehow isn't there yet.
                onOpenMangaSources = {
                    val shelfRepo = sourcesViewModel.state.value
                        .repos.firstOrNull { repo ->
                            app.xreader.data.extension
                                .KeiyoushiIndex.isKeiyoushiRepo(
                                    repo.name, repo.indexUrl
                                )
                        }
                    if (shelfRepo != null) {
                        navController.navigate("repo/${shelfRepo.id}")
                    } else {
                        navController.navigate("sourceStore")
                    }
                },
                opdsServers = opdsServers,
                onOpenOpdsServer = { server ->
                    opdsViewModel.openServer(server)
                    navController.navigate("opds/${server.id}")
                },
                onOpenBook = { bookId ->
                    navigateContent("book/$bookId")
                },
                coverFile = coverFile
            )
        }
        composable("manageSources") {
            // v1.5.2 (FIX-15, round-8 placement rule): no
            // consumer outside this route, so the model is
            // created HERE, localised — not threaded through
            // the shells from the host.
            val manageSourcesViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel<app.xreader.ui.sources.ManageSourcesViewModel>(
                    factory = app.xreader.ui.sources.ManageSourcesViewModel.factory(container)
                )
            app.xreader.ui.sources.ManageSourcesScreen(
                viewModel = manageSourcesViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("addSource") {
            app.xreader.ui.sources.AddSourceScreen(
                viewModel = sourcesViewModel,
                onBack = { navController.popBackStack() },
                onAddFromLink = {
                    // v1.4.3: file by ENTRY SECTION — the
                    // section showing when "Add a link" was
                    // tapped is authoritative for the saved
                    // source's kind (Manga → manga source,
                    // Novels → novel source).
                    linkAddViewModel.setIntendedKind(
                        sourcesViewModel.state.value.librarySide
                    )
                    navController.navigate("addFromLink")
                },
                onOpenStore = { navController.navigate("sourceStore") },
                onOpenRepo = { repoId ->
                    navController.navigate("repo/$repoId")
                }
            )
        }
        composable("sourceStore") {
            app.xreader.ui.sources.SourceStoreScreen(
                viewModel = sourcesViewModel,
                packViewModel = packViewModel,
                onBack = { navController.popBackStack() },
                onOpenRepo = { repoId ->
                    navController.navigate("repo/$repoId")
                },
                onOpenBuiltIn = { navController.navigate("repoBuiltIn") },
                onAddRepo = { navController.navigate("addSource") }
            )
        }
        // v1.3.2 (scope E): one repository's own screen — its
        // full source list, searchable, novels-led.
        composable(
            "repo/{repoId}",
            arguments = listOf(
                androidx.navigation.navArgument("repoId") {
                    type = androidx.navigation.NavType.LongType
                }
            )
        ) { entry ->
            val repoId = entry.arguments?.getLong("repoId") ?: 0L
            val repoState by sourcesViewModel.state
                .collectAsStateWithLifecycle()
            val repo = repoState.repos.firstOrNull { it.id == repoId }
            app.xreader.ui.sources.RepoSourcesScreen(
                viewModel = sourcesViewModel,
                repo = repo,
                onBack = { navController.popBackStack() },
                onOpenExtension = { extId ->
                    navController.navigate("extensions/$extId")
                }
            )
        }
        composable("repoBuiltIn") {
            app.xreader.ui.sources.RepoSourcesScreen(
                viewModel = sourcesViewModel,
                repo = null,
                onBack = { navController.popBackStack() },
                onOpenExtension = { extId ->
                    navController.navigate("extensions/$extId")
                },
                builtIn = true
            )
        }
        composable("opdsServers") {
            BrowseScreen(
                viewModel = opdsViewModel,
                onOpenServer = { server ->
                    opdsViewModel.openServer(server)
                    navController.navigate("opds/${server.id}")
                }
            )
        }
        composable(
            "source/{extId}/{sourceId}",
            arguments = listOf(
                androidx.navigation.navArgument("extId") {
                    type = androidx.navigation.NavType.LongType
                },
                androidx.navigation.navArgument("sourceId") {
                    type = androidx.navigation.NavType.StringType
                }
            )
        ) { entry ->
            val extId = entry.arguments?.getLong("extId") ?: 0L
            val sourceId = entry.arguments?.getString("sourceId") ?: ""
            val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<SourceCatalogViewModel>(
                key = "source-$extId-$sourceId",
                factory = SourceCatalogViewModel.factory(container, extId, sourceId)
            )
            androidx.compose.runtime.LaunchedEffect(viewModel) {
                viewModel.messages.collect { msg -> onPhaseNote(msg) }
            }
            SourceCatalogScreen(
                viewModel = viewModel,
                extensionId = extId,
                onBack = { navController.popBackStack() },
                onOpenBook = { bookId -> navigateContent("book/$bookId") },
                // v1.3.2 (scope A2): the catalogue's hand-off.
                onSessionHandoff = { kind ->
                    openSessionHandoff(extId, kind, null)
                },
                // Keiyoushi player (K5): the source's own
                // settings (its extension preferences).
                onOpenSourceSettings = {
                    navController.navigate(
                        "sourcePrefs/$extId/${android.net.Uri.encode(sourceId)}"
                    )
                }
            )
        }
        // Keiyoushi player (K5): one player source's own
        // settings — the extension's preference screen, hosted
        // headlessly and rendered natively.
        composable(
            "sourcePrefs/{extId}/{sourceId}",
            arguments = listOf(
                androidx.navigation.navArgument("extId") {
                    type = androidx.navigation.NavType.LongType
                },
                androidx.navigation.navArgument("sourceId") {
                    type = androidx.navigation.NavType.StringType
                }
            )
        ) { entry ->
            val extId = entry.arguments?.getLong("extId") ?: 0L
            val sourceId = entry.arguments?.getString("sourceId") ?: ""
            val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<app.xreader.ui.sources.SourcePreferencesViewModel>(
                key = "source-prefs-$extId-$sourceId",
                factory = app.xreader.ui.sources.SourcePreferencesViewModel.factory(container, extId, sourceId)
            )
            androidx.compose.runtime.LaunchedEffect(viewModel) {
                viewModel.messages.collect { msg -> onPhaseNote(msg) }
            }
            app.xreader.ui.sources.SourcePreferencesScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            "extensions/{extId}",
            arguments = listOf(
                androidx.navigation.navArgument("extId") {
                    type = androidx.navigation.NavType.LongType
                }
            )
        ) { entry ->
            val extId = entry.arguments?.getLong("extId") ?: 0L
            val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<ExtensionDetailViewModel>(
                key = "ext-detail-$extId",
                factory = ExtensionDetailViewModel.factory(container, extId)
            )
            androidx.compose.runtime.LaunchedEffect(viewModel) {
                viewModel.messages.collect { msg -> onPhaseNote(msg) }
            }
            ExtensionDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onLogin = {
                    val ext = viewModel.state.value.extension
                    if (ext != null) {
                        val firstHost = app.xreader.data.extension.ExtensionPermissions
                            .parse(ext.permissionsJson).domains.firstOrNull()
                        if (firstHost != null) {
                            // v1.0.6 (W4.3): a browser-view source
                            // whose health says NEEDS_SETUP almost
                            // always wants the site's human check,
                            // not a password — open in that mode.
                            val detailState = viewModel.state.value
                            sourcesViewModel.startLogin(
                                sessionKey = sourcesViewModel.sessionKeyFor(extId),
                                url = "https://$firstHost",
                                title = ext.displayName,
                                mode = if (
                                    detailState.linkConfig?.needsRendering == true &&
                                    detailState.health?.state ==
                                    app.xreader.data.extension.SourceHealthState.NEEDS_SETUP
                                ) {
                                    app.xreader.ui.sources.LoginMode.SITE_CHECK
                                } else {
                                    app.xreader.ui.sources.LoginMode.SIGN_IN
                                }
                            )
                            navController.navigate("login")
                        } else {
                            onPhaseNote("This source doesn't declare a site to sign in to")
                        }
                    }
                },
                onOpenSource = { sourceId ->
                    navigateContent("source/$extId/${android.net.Uri.encode(sourceId)}")
                }
            )
        }
        composable("login") {
            val request by sourcesViewModel.loginRequest
                .collectAsStateWithLifecycle()
            val current = request
            // v1.3.3 (scope S4): EXACTLY ONE pop. v1.3.2 popped
            // in onDone AND again from this null branch when
            // clearing the request recomposed the destination —
            // the double pop landed the user past the book page
            // on a blank screen after a successful sign-in.
            // Now the buttons only clear the request; this
            // branch owns the single pop, and only when a
            // request was actually shown (a stray navigation
            // to "login" with nothing pending must not eat a
            // back-stack entry either).
            var sawRequest by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(false)
            }
            if (current != null) sawRequest = true
            androidx.compose.runtime.LaunchedEffect(current == null) {
                if (current == null && sawRequest) {
                    sawRequest = false
                    navController.popBackStack()
                }
            }
            if (current != null) {
                LoginScreen(
                    request = current,
                    viewModel = sourcesViewModel,
                    onDone = {
                        sourcesViewModel.clearLoginRequest()
                    },
                    onCancel = {
                        sourcesViewModel.clearLoginRequest()
                    }
                )
            }
        }
        composable("addFromLink") {
            AddFromLinkScreen(
                viewModel = linkAddViewModel,
                sessionSaved = sourcesViewModel.sessionSaved,
                onRequestLogin = { loginRequest ->
                    sourcesViewModel.startLogin(
                        sessionKey = loginRequest.sessionKey,
                        url = loginRequest.url,
                        title = loginRequest.title,
                        mode = loginRequest.mode
                    )
                    navController.navigate("login")
                },
                onOpenBook = { bookId -> navigateContent("book/$bookId") },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            "opds/{serverId}",
            arguments = listOf(
                androidx.navigation.navArgument("serverId") {
                    type = androidx.navigation.NavType.LongType
                }
            )
        ) { entry ->
            val serverName = opdsViewModel.servers.value
                .firstOrNull { it.id == entry.arguments?.getLong("serverId") }?.name ?: "Catalog"
            OpdsCatalogScreen(
                viewModel = opdsViewModel,
                serverName = serverName,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Tab.Downloads.route) {
            DownloadsScreen(
                viewModel = downloadsViewModel,
                coverFile = coverFile,
                reduceMotion = reduceMotion,
                onMakeEpub = { group: BookDownloads -> onExport(group.book, "epub") },
                onOpenBook = { bookId -> navigateContent("book/$bookId") },
                onOpenCustomise = { navController.navigate("customise") }
            )
        }
        composable(Tab.Me.route) {
            // The Me tab (R08): the v1.0.1 "More" hub, dissolved into
            // Me — same destinations, now behind the labelled tab the
            // user asked for. R06 grows this into the full Me home.
            val profileViewModel = androidx.lifecycle.viewmodel.compose
                .viewModel<app.xreader.ui.profile.ProfileViewModel>(
                    key = "me-header",
                    factory = app.xreader.ui.profile.ProfileViewModel.factory(container)
                )
            val meState by profileViewModel.state.collectAsStateWithLifecycle()
            // R28: the What's New note shows until dismissed for
            // the installed version code.
            val whatsNewSeen by container.gamePrefs.whatsNewSeenCode
                .collectAsStateWithLifecycle(initialValue = Int.MAX_VALUE)
            val installedCode = remember {
                runCatching {
                    @Suppress("DEPRECATION")
                    container.applicationContext.packageManager.getPackageInfo(
                        container.applicationContext.packageName, 0
                    ).versionCode
                }.getOrDefault(0)
            }
            val meScope = androidx.compose.runtime.rememberCoroutineScope()
            // v1.0.5 (E1): the heal sweep's one calm line, until
            // dismissed.
            val healNote by container.sourcePrefs.healNote
                .collectAsStateWithLifecycle(initialValue = null)
            MoreScreen(
                onAppearance = { navController.navigate("appearance") },
                onLabs = { navController.navigate("labs") },
                onAbout = { navController.navigate("about") },
                onProviders = { navController.navigate("providers") },
                onSync = { navController.navigate("sync") },
                onProfile = { navController.navigate("profile") },
                onMigration = { navController.navigate("migration") },
                onHistory = { navController.navigate("history") },
                onCustomise = { navController.navigate("customise") },
                onVocab = { navController.navigate("vocab") },
                onUpdatesAbout = { navController.navigate("appUpdates") },
                onReaderTerms = { navController.navigate("terms") },
                meName = meState.profile?.displayName ?: "",
                meLevel = meState.profile?.level ?: 1,
                meTitle = meState.title,
                meStreak = meState.stats.currentStreak,
                whatsNewVisible = installedCode > 0 && whatsNewSeen < installedCode,
                onOpenWhatsNew = { navController.navigate("about") },
                onDismissWhatsNew = {
                    meScope.launch {
                        container.gamePrefs.setWhatsNewSeen(installedCode)
                    }
                },
                healNote = healNote,
                onDismissHealNote = {
                    meScope.launch { container.sourcePrefs.setHealNote(null) }
                }
            )
        }
        composable("more") {
            // Legacy route (old internal links / widget era): never a
            // dead end — land on the Me tab.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                navigateToTab(navController, Tab.Me)
            }
        }
        // v1.3.2 (scope D): the standalone "globalSearch" route
        // is retired — searching every source happens in Explore
        // itself now (the field at its top). Old internal links
        // land on Explore rather than a dead end.
        composable("globalSearch") {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                navigateToTab(navController, Tab.Browse)
            }
        }
        composable(Tab.History.route) {
            // History is a first-class tab now (R08); its header back
            // arrow returns to Library instead of popping (a tab has
            // nothing to pop to).
            val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<
                app.xreader.ui.more.HistoryViewModel>(
                factory = app.xreader.ui.more.HistoryViewModel.factory(container)
            )
            app.xreader.ui.more.HistoryScreen(
                viewModel = viewModel,
                coverFile = coverFile,
                reduceMotion = reduceMotion,
                onBack = { navigateToTab(navController, Tab.Library) },
                // v1.5.6 (§2.2 entry point 3): a History tap
                // resolves through the routing law.
                onOpenBook = { bookId, chapterIndex ->
                    openReader(bookId, chapterIndex)
                }
            )
        }
        composable("customise") {
            val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<
                app.xreader.ui.more.CustomiseViewModel>(
                factory = app.xreader.ui.more.CustomiseViewModel.factory(container)
            )
            app.xreader.ui.more.CustomiseScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("profile") {
            val viewModel = androidx.lifecycle.viewmodel.compose
                .viewModel<app.xreader.ui.profile.ProfileViewModel>(
                    factory = app.xreader.ui.profile.ProfileViewModel.factory(container)
                )
            app.xreader.ui.profile.ProfileScreen(
                viewModel = viewModel,
                container = container,
                onBack = { navController.popBackStack() },
                reduceMotion = reduceMotion,
                lowRam = remember {
                    val manager = navController.context
                        .getSystemService(android.content.Context.ACTIVITY_SERVICE)
                        as android.app.ActivityManager
                    val info = android.app.ActivityManager.MemoryInfo()
                    manager.getMemoryInfo(info)
                    info.totalMem / (1024L * 1024L) <= 2048L
                },
                onOpenHistory = { navController.navigate("history") },
                onOpenSync = { navController.navigate("sync") },
                onOpenCustomise = { navController.navigate("customise") },
                onOpenVocab = { navController.navigate("vocab") }
            )
        }
        composable("migration") {
            val viewModel = androidx.lifecycle.viewmodel.compose
                .viewModel<app.xreader.ui.migration.MigrationViewModel>(
                    factory = app.xreader.ui.migration.MigrationViewModel.factory(container)
                )
            app.xreader.ui.migration.MigrationScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onAddFromLink = {
                    // Migration isn't section-scoped: clear any
                    // section stamp from an earlier add so the
                    // learner's own default decides here.
                    linkAddViewModel.setIntendedKind(null)
                    navController.navigate("addFromLink")
                }
            )
        }
        composable("providers") {
            val viewModel = androidx.lifecycle.viewmodel.compose
                .viewModel<app.xreader.ui.ai.ProvidersViewModel>(
                    factory = app.xreader.ui.ai.ProvidersViewModel.factory(container)
                )
            app.xreader.ui.ai.ProvidersScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("sync") {
            app.xreader.ui.sync.SyncScreen(
                viewModel = syncViewModel,
                container = container,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            "lore/{bookId}",
            arguments = listOf(
                androidx.navigation.navArgument("bookId") {
                    type = androidx.navigation.NavType.LongType
                }
            )
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: 0L
            val viewModel = androidx.lifecycle.viewmodel.compose
                .viewModel<app.xreader.ui.ai.LoreViewModel>(
                    key = "lore-$bookId",
                    factory = app.xreader.ui.ai.LoreViewModel.factory(container, bookId)
                )
            app.xreader.ui.ai.LoreScreen(
                viewModel = viewModel,
                artFile = { path -> container.bookStorage.resolve(path) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            "book/{bookId}",
            arguments = listOf(
                androidx.navigation.navArgument("bookId") {
                    type = androidx.navigation.NavType.LongType
                }
            )
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: 0L
            val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<BookDetailViewModel>(
                key = "book-$bookId",
                factory = BookDetailViewModel.factory(container, bookId)
            )
            androidx.compose.runtime.LaunchedEffect(viewModel) {
                viewModel.messages.collect { msg -> onPhaseNote(msg) }
            }
            // v1.1.0 (Nocturne §3): the frozen book page keeps the
            // colours it had before the flip — the pin wrapper
            // resolves the migration snapshot for this subtree
            // only. BookDetailScreen.kt itself is untouched.
            val bookAppearance by container.appearancePrefs.state
                .collectAsStateWithLifecycle(
                    initialValue = app.xreader.data.prefs.AppearanceState()
                )
            app.xreader.ui.book.BookDetailTheme(appearance = bookAppearance) {
            BookDetailScreen(
                viewModel = viewModel,
                coverFile = coverFile,
                assetFile = assetFile,
                onBack = { navController.popBackStack() },
                onOpenLore = { navController.navigate("lore/$bookId") },
                onOpenRelated = { relatedId -> navigateContent("book/$relatedId") },
                // v1.5.6 (§2.2 entry point 1): the book page's
                // chapter tap / Read button resolves through the
                // routing law — a manga book opens Komikku
                // directly from here, the novel reader never
                // appears first.
                onReadChapter = { chapterIndex ->
                    openReader(bookId, chapterIndex)
                },
                // v1.5.6 (§3.2): a Contents tap on an in-place
                // local book carries the outline entry's own
                // target JSON straight to the local reader —
                // the shared chapter door's index cannot express
                // it (the LocalDoc verdict is book-scoped).
                onReadLocalTarget = { id, targetJson ->
                    navController.navigate(
                        "local/$id?target=" +
                            android.net.Uri.encode(targetJson)
                    )
                },
                onExportEpub = {
                    val book = viewModel.state.value.book
                    if (book != null) onExport(book, "epub") else onPhaseNote("Book not loaded yet")
                },
                onExportCbz = {
                    val book = viewModel.state.value.book
                    if (book != null) onExport(book, "cbz") else onPhaseNote("Book not loaded yet")
                },
                onOpenTag = { slug ->
                    navController.navigate(
                        "tag/${android.net.Uri.encode(slug)}"
                    )
                },
                onFindTitle = { title ->
                    // v1.3.2 (scope D): the preset lands on the
                    // EXPLORE entry and runs in its search field.
                    runCatching {
                        navController.getBackStackEntry(Tab.Browse.route)
                            .savedStateHandle["presetQuery"] = title
                    }
                    navigateToTab(navController, Tab.Browse)
                },
                // v1.0.5 (B1): the Chapters section's Repair door
                // lands on the source's Details page.
                onOpenSourceDetails = { extId ->
                    navController.navigate("extensions/$extId")
                },
                // v1.0.6 (W4.3): the site-check door opens the
                // app-styled check screen for the book's source.
                onSiteCheck = { extId ->
                    scope.launch {
                        val ext = container.database
                            .installedExtensionDao().getById(extId)
                        val firstHost = ext?.let {
                            app.xreader.data.extension.ExtensionPermissions
                                .parse(it.permissionsJson)
                                .domains.firstOrNull()
                        }
                        if (ext != null && firstHost != null) {
                            sourcesViewModel.startLogin(
                                sessionKey = sourcesViewModel
                                    .sessionKeyFor(extId),
                                url = "https://$firstHost",
                                title = ext.displayName,
                                mode = app.xreader.ui.sources
                                    .LoginMode.SITE_CHECK
                            )
                            navController.navigate("login")
                        } else {
                            onPhaseNote(
                                "This source doesn't declare a site to check"
                            )
                        }
                    }
                },
                // v1.3.2 (scope A2): the session hand-off.
                onSessionHandoff = { extId, kind ->
                    openSessionHandoff(extId, kind, null)
                }
            )
            }
        }
        composable(
            "tag/{slug}",
            arguments = listOf(
                androidx.navigation.navArgument("slug") {
                    type = androidx.navigation.NavType.StringType
                }
            )
        ) { entry ->
            val slug = entry.arguments?.getString("slug") ?: ""
            val viewModel = androidx.lifecycle.viewmodel.compose
                .viewModel<app.xreader.ui.library.TagPageViewModel>(
                    key = "tag-$slug",
                    factory = app.xreader.ui.library.TagPageViewModel
                        .factory(container, slug)
                )
            app.xreader.ui.library.TagPageScreen(
                viewModel = viewModel,
                coverFile = coverFile,
                onBack = { navController.popBackStack() },
                onOpenBook = { id -> navigateContent("book/$id") }
            )
        }
        composable("vocab") {
            val viewModel = androidx.lifecycle.viewmodel.compose
                .viewModel<app.xreader.ui.library.VocabViewModel>(
                    factory = app.xreader.ui.library.VocabViewModel
                        .factory(container)
                )
            app.xreader.ui.library.VocabScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("terms") {
            val viewModel = androidx.lifecycle.viewmodel.compose
                .viewModel<app.xreader.ui.more.TermsViewModel>(
                    factory = app.xreader.ui.more.TermsViewModel
                        .factory(container)
                )
            app.xreader.ui.more.TermsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            "reader/{bookId}/{chapterIndex}",
            arguments = listOf(
                androidx.navigation.navArgument("bookId") {
                    type = androidx.navigation.NavType.LongType
                },
                androidx.navigation.navArgument("chapterIndex") {
                    type = androidx.navigation.NavType.IntType
                }
            )
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: 0L
            val chapterIndex = entry.arguments?.getInt("chapterIndex") ?: 0
            val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<ReaderViewModel>(
                key = "reader-$bookId-$chapterIndex",
                factory = ReaderViewModel.factory(container, bookId, chapterIndex)
            )
            ReaderScreen(
                viewModel = viewModel,
                container = container,
                reduceMotion = reduceMotion,
                assetFile = assetFile,
                bookId = bookId,
                onBack = { navController.popBackStack() },
                onOpenCustomise = { navController.navigate("customise") },
                onOpenAiPage = {
                    navController.navigate("ai/$bookId/$chapterIndex")
                },
                onSessionHandoff = { kind, targetUrl ->
                    // The book's source owns the session; resolve
                    // its extension row from the book itself.
                    // v1.3.4 (U3): the reader page target (when
                    // the source declares one) rides along.
                    scope.launch {
                        val bookRow = container.database.bookDao()
                            .getById(bookId)
                        val extId = bookRow?.extensionId
                        if (extId != null) {
                            openSessionHandoff(extId, kind, targetUrl)
                        } else {
                            onPhaseNote("This book's source can't be " +
                                "opened for sign-in.")
                        }
                    }
                }
            )
        }
        composable(
            "local/{bookId}?target={target}",
            arguments = listOf(
                androidx.navigation.navArgument("bookId") {
                    type = androidx.navigation.NavType.LongType
                },
                androidx.navigation.navArgument("target") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            // v1.5.6 (§3): the render-the-file reader. LocalDoc
            // verdicts land here (dispatchReaderRoute); the
            // optional target is an outline target JSON (e.g.
            // {"section": 4}) when the book page's Contents list
            // was tapped. With no target the reader resumes from
            // saved progress.
            val bookId = entry.arguments?.getLong("bookId") ?: 0L
            val target = entry.arguments?.getString("target")
            app.xreader.ui.localreader.LocalDocReaderScreen(
                container = container,
                bookId = bookId,
                startTargetJson = target,
                onClose = { navController.popBackStack() }
            )
        }
        composable(
            "ai/{bookId}/{chapterIndex}",
            arguments = listOf(
                androidx.navigation.navArgument("bookId") {
                    type = androidx.navigation.NavType.LongType
                },
                androidx.navigation.navArgument("chapterIndex") {
                    type = androidx.navigation.NavType.IntType
                }
            )
        ) { entry ->
            // v1.0.6.2 (F9b): Ask AI is a full page now. It shares
            // the READER entry's view models (same AiSheetViewModel
            // the reader bound to the chapter on screen, same
            // ReaderViewModel for the translation toggles), so the
            // features behave exactly as they did in the sheet.
            val bookId = entry.arguments?.getLong("bookId") ?: 0L
            val chapterIndex = entry.arguments?.getInt("chapterIndex") ?: 0
            val readerEntry = runCatching {
                navController.getBackStackEntry("reader/{bookId}/{chapterIndex}")
            }.getOrNull()
            if (readerEntry == null) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
                return@composable
            }
            val readerViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel<ReaderViewModel>(
                    viewModelStoreOwner = readerEntry,
                    key = "reader-$bookId-$chapterIndex",
                    factory = ReaderViewModel.factory(container, bookId, chapterIndex)
                )
            val aiViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel<
                    app.xreader.ui.ai.AiSheetViewModel>(
                    viewModelStoreOwner = readerEntry,
                    key = "ai-$bookId",
                    factory = app.xreader.ui.ai.AiSheetViewModel
                        .factory(container, bookId)
                )
            val readerState by readerViewModel.state
                .collectAsStateWithLifecycle()
            app.xreader.ui.ai.AiPage(
                viewModel = aiViewModel,
                showingTranslation = readerState.showingTranslation,
                onShowOriginal = { readerViewModel.showOriginalChapter() },
                onShowTranslation = { readerViewModel.showTranslatedChapter() },
                onBack = { navController.popBackStack() }
            )
        }
        composable("appearance") {
            // R07: "Appearance" ceased to be a separate door — its
            // controls (theme mode, reduce motion) moved into
            // Customise, and this legacy route lands there instead
            // of dead-ending (the More hub's old link still works).
            androidx.compose.runtime.LaunchedEffect(Unit) {
                navController.navigate("customise") {
                    popUpTo("appearance") { inclusive = true }
                }
            }
        }
        composable("labs") {
            LabsScreen(container = container, onBack = { navController.popBackStack() })
        }
        composable("about") {
            AboutScreen(
                onBack = { navController.popBackStack() },
                container = container
            )
        }
        // v1.3.5: Settings → "Updates & about" — the one
        // section for the check, the switch, our links and
        // the app details.
        composable("appUpdates") {
            val updateState by appUpdateViewModel.state
                .collectAsStateWithLifecycle()
            app.xreader.ui.update.UpdatesAboutScreen(
                state = updateState,
                onBack = { navController.popBackStack() },
                onCheckNow = { appUpdateViewModel.checkManually() },
                onAutoCheckChange = { appUpdateViewModel.setAutoCheck(it) },
                onOpenWhatsNew = { navController.navigate("whatsNew") },
                onOpenAbout = { navController.navigate("about") }
            )
        }
        // v1.3.5: the full release notes, read inside the
        // app — the update card's Read more lands here.
        composable("whatsNew") {
            val updateState by appUpdateViewModel.state
                .collectAsStateWithLifecycle()
            app.xreader.ui.update.WhatsNewScreen(
                state = updateState,
                onBack = { navController.popBackStack() },
                onUpdateNow = { appUpdateViewModel.startDownload() },
                onInstall = { appUpdateViewModel.install() },
                onOpenInstallSettings = {
                    appUpdateViewModel.openInstallPermissionSettings()
                }
            )
        }
    }
}

@Composable
private fun selectedTab(navController: NavHostController): Tab {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    return when {
        route == "appearance" || route == "about" || route == "labs" ||
            route == "providers" || route == "sync" || route == "profile" ||
            route == "migration" || route == "customise" || route == "more" ||
            route == "terms" -> Tab.Me
        route?.startsWith("book/") == true || route?.startsWith("reader/") == true ||
            route?.startsWith("ai/") == true ||
            route?.startsWith("lore/") == true || route == "updates" ||
            route?.startsWith("tag/") == true || route == "vocab" -> Tab.Library
        route?.startsWith("opds/") == true || route == "opdsServers" ||
            route?.startsWith("source/") == true || route?.startsWith("extensions/") == true ||
            route == "login" || route == "addFromLink" -> Tab.Browse
        else -> Tab.entries.firstOrNull { it.route == route } ?: Tab.Library
    }
}

private fun navigateToTab(navController: NavHostController, tab: Tab) {
    navController.navigate(tab.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * v1.5.6 (§2): the shell's reader-route resolver, built from the
 * container's own seams. (Hoisting this onto AppContainer as a
 * lazy `readerRouteResolver` is the coordinator's wiring step;
 * the construction here uses exactly the container's database
 * DAOs + extension service, so both wirings are identical.)
 */
private fun readerRouteResolverFor(
    container: AppContainer
): app.xreader.domain.reader.ReaderRouteResolver =
    app.xreader.domain.reader.ReaderRouteResolver(
        bookDao = container.database.bookDao(),
        chapterDao = container.database.chapterDao(),
        extensionService = container.extensionService
    )

/**
 * v1.5.6 (§2): dispatch ONE resolver verdict — the only way a
 * chapter open leaves the shell. A Komikku verdict launches the
 * Komikku reader Activity directly and NEVER transits the
 * "reader/…" route; a novel verdict keeps the existing route;
 * a local-document verdict navigates to "local/<bookId>" (the
 * §3 destination, registered by the local-reader workstream —
 * the route string is the contract).
 */
/**
 * v1.5.8 (F1): the shell target ONE resolver verdict resolves to —
 * the concrete NavHost route for in-shell verdicts, or null for
 * the Komikku verdict, which leaves the shell entirely (its own
 * Activity, so the shell can never put chrome around it: immersive
 * by construction). This is the single source of the route
 * strings [dispatchReaderRoute] navigates to, and ShellChromeTest's
 * enumeration test drives every verdict shape through it: a future
 * reader verdict that lands on a non-immersive route (or grows a
 * route at all without registering it) fails that test.
 */
internal fun readerRouteShellTarget(
    route: app.xreader.domain.reader.ReaderRoute
): String? = when (route) {
    is app.xreader.domain.reader.ReaderRoute.Komikku -> null
    is app.xreader.domain.reader.ReaderRoute.NovelReader ->
        "reader/${route.bookId}/${route.chapterIndex}"
    is app.xreader.domain.reader.ReaderRoute.LocalDoc ->
        "local/${route.bookId}"
}

private fun dispatchReaderRoute(
    route: app.xreader.domain.reader.ReaderRoute,
    context: android.content.Context,
    navigateContent: (String) -> Unit
) {
    when (route) {
        is app.xreader.domain.reader.ReaderRoute.Komikku ->
            context.startActivity(
                app.xreader.komikku.KomikkuReaderActivity.newIntent(
                    context,
                    mangaId = route.bookId,
                    chapterId = route.chapterRowId
                )
            )
        is app.xreader.domain.reader.ReaderRoute.NovelReader,
        is app.xreader.domain.reader.ReaderRoute.LocalDoc ->
            navigateContent(readerRouteShellTarget(route)!!)
    }
}

/**
 * v1.5.6 (§2): resolve + dispatch for one chapter open. A
 * resolver failure falls back to the novel reader (the
 * long-standing default) rather than stranding the tap; the
 * novel reader's fail-closed guard (§2.1) backstops a manga
 * book that ever lands there.
 */
private suspend fun openReaderViaResolver(
    resolver: app.xreader.domain.reader.ReaderRouteResolver,
    bookId: Long,
    chapterIndex: Int,
    context: android.content.Context,
    navigateContent: (String) -> Unit
) {
    val route = try {
        // v1.5.7 (scope F): resolve off the main thread — a cold
        // ExtensionService.source() does blocking file IO + JSON
        // decode (and rebuilds Keiyoushi adapters on every call),
        // which froze book-open transitions when run on Main.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            resolver.resolve(bookId, chapterIndex)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        app.xreader.domain.reader.ReaderRoute.NovelReader(bookId, chapterIndex)
    }
    dispatchReaderRoute(route, context, navigateContent)
}

/**
 * The floating frosted bottom bar. Height is a CONSTANT (64 dp): the
 * Scaffold bottomBar slot permits full-screen height, so nothing in
 * here may size from the slot's constraints — the v1.1.1 lesson.
 */
@Composable
private fun FloatingNavBar(
    selected: Tab,
    reduceMotion: Boolean,
    onSelect: (Tab) -> Unit,
    barStyle: app.xreader.data.library.NavBarStyle =
        app.xreader.data.library.NavBarStyle.FLOATING_PILL,
    labelStyle: app.xreader.data.library.TabLabelStyle =
        app.xreader.data.library.TabLabelStyle.ALWAYS,
    /** v1.5.39 (item 4): pending manga-source updates,
     * badged numerically on the Explore tab (0 = no badge). */
    exploreBadgeCount: Int = 0
) {
    val glass = LocalGlassSettings.current
    val density = LocalDensity.current
    var barWidthPx by remember { mutableIntStateOf(0) }
    val barHeightPx = with(density) { BarHeight.roundToPx() }
    val hInsetPx = with(density) { 6.dp.roundToPx() }
    val vInsetPx = with(density) { 6.dp.roundToPx() }
    val itemCount = Tab.entries.size
    val selectedIndex = Tab.entries.indexOf(selected)

    val pillWidth = NavGeometry.pillWidthPx(barWidthPx, itemCount, hInsetPx)
    val pillHeight = NavGeometry.pillHeightPx(barHeightPx, vInsetPx)
    val targetX = NavGeometry.pillTargetXPx(barWidthPx, itemCount, selectedIndex, hInsetPx)
    val animatedX by animateFloatAsState(
        targetValue = targetX.toFloat(),
        animationSpec = if (reduceMotion) {
            androidx.compose.animation.core.snap()
        } else {
            specFor(SpringMotionEngine.Spatial, SpringMotionEngine.MassClass.Light)
        },
        label = "navPillX"
    )
    val pillX = NavGeometry.clampPillXPx(animatedX, barWidthPx, pillWidth)
    val pillY = NavGeometry.pillTargetYPx(barHeightPx, vInsetPx)

    val docked = barStyle == app.xreader.data.library.NavBarStyle.DOCKED_BAR
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // v1.0.6.2 (F7 lift): the floating pill floats the
            // shared extra amount above the navigation inset —
            // the same height language as the reader's bottom bar.
            .padding(
                start = if (docked) 0.dp else 16.dp,
                end = if (docked) 0.dp else 16.dp,
                top = if (docked) 0.dp else 10.dp,
                bottom = if (docked) {
                    0.dp
                } else {
                    10.dp + app.xreader.ui.theme.FloatingChromeLift
                }
            )
    ) {
        Surface(
            shape = if (docked) RoundedCornerShape(0.dp) else RoundedCornerShape(50),
            color = glass.glassSurface(MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outline
            ),
            shadowElevation = glass.raisedElevation,
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .frostedGlass(glass, MaterialTheme.colorScheme.surface)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { barWidthPx = it.width }
            ) {
                if (pillWidth > 0 && pillHeight > 0) {
                    Box(
                        Modifier
                            .offset { IntOffset(pillX.roundToInt(), pillY) }
                            .size(
                                width = with(density) { pillWidth.toDp() },
                                height = with(density) { pillHeight.toDp() }
                            )
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize()
                        ) {}
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    Tab.entries.forEach { tab ->
                        NavSlot(
                            tab = tab,
                            selected = tab == selected,
                            onClick = { onSelect(tab) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            labelStyle = labelStyle,
                            badgeCount = if (tab == Tab.Browse) {
                                exploreBadgeCount
                            } else {
                                0
                            }
                        )
                    }
                }
            }
        }
    }
}

/** The tablet left rail: fixed width, fixed-height slots stacked from the
 * top, "+ Add a book" pinned at the base (DESIGN.md). The pill's rect is
 * [NavGeometry] rail math over the deterministic slots region. */
@Composable
private fun NavRail(
    selected: Tab,
    reduceMotion: Boolean,
    onSelect: (Tab) -> Unit,
    onAddBook: () -> Unit,
    labelStyle: app.xreader.data.library.TabLabelStyle =
        app.xreader.data.library.TabLabelStyle.ALWAYS,
    /** v1.5.39 (item 4): see [FloatingNavBar]. */
    exploreBadgeCount: Int = 0
) {
    val glass = LocalGlassSettings.current
    val density = LocalDensity.current
    val itemCount = Tab.entries.size
    val slotPx = with(density) { RailSlotHeight.roundToPx() }
    val railWidthPx = with(density) { RailWidth.roundToPx() }
    val slotsHeightPx = slotPx * itemCount
    val hInsetPx = with(density) { 10.dp.roundToPx() }
    val vInsetPx = with(density) { 8.dp.roundToPx() }
    val selectedIndex = Tab.entries.indexOf(selected)

    val pillWidth = NavGeometry.pillWidthRailPx(railWidthPx, hInsetPx)
    val pillHeight = NavGeometry.pillHeightRailPx(slotsHeightPx, itemCount, vInsetPx)
    val targetY = NavGeometry.pillTargetYRailPx(slotsHeightPx, itemCount, selectedIndex, vInsetPx)
    val animatedY by animateFloatAsState(
        targetValue = targetY.toFloat(),
        animationSpec = if (reduceMotion) {
            androidx.compose.animation.core.snap()
        } else {
            specFor(SpringMotionEngine.Spatial, SpringMotionEngine.MassClass.Light)
        },
        label = "railPillY"
    )
    val pillY = NavGeometry.clampPillYPx(animatedY, slotsHeightPx, pillHeight)
    val pillX = NavGeometry.pillTargetXRailPx(railWidthPx, hInsetPx)

    Surface(
        color = glass.glassSurface(MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = glass.cardElevation,
        modifier = Modifier
            .width(RailWidth)
            .fillMaxHeight()
            .frostedGlass(glass, MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.width(RailWidth).height(RailSlotHeight * itemCount)) {
                if (pillWidth > 0 && pillHeight > 0) {
                    Box(
                        Modifier
                            .offset { IntOffset(pillX, pillY.roundToInt()) }
                            .size(
                                width = with(density) { pillWidth.toDp() },
                                height = with(density) { pillHeight.toDp() }
                            )
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize()
                        ) {}
                    }
                }
                Column {
                    Tab.entries.forEach { tab ->
                        NavSlot(
                            tab = tab,
                            selected = tab == selected,
                            onClick = { onSelect(tab) },
                            modifier = Modifier
                                .width(RailWidth)
                                .height(RailSlotHeight),
                            labelStyle = labelStyle,
                            badgeCount = if (tab == Tab.Browse) {
                                exploreBadgeCount
                            } else {
                                0
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            XreaderPrimaryButton(
                text = "+ Add a book",
                onClick = onAddBook,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

@Composable
private fun NavSlot(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelStyle: app.xreader.data.library.TabLabelStyle =
        app.xreader.data.library.TabLabelStyle.ALWAYS,
    /** v1.5.39 (item 4): a numeric badge over the icon
     * (the Explore tab's pending source updates; 0 = none). */
    badgeCount: Int = 0
) {
    val interaction = remember { MutableInteractionSource() }
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .pressBounce(interaction, haptics = false)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        if (badgeCount > 0) {
            androidx.compose.material3.BadgedBox(
                badge = {
                    androidx.compose.material3.Badge {
                        Text(badgeCount.toString())
                    }
                }
            ) {
                Icon(tab.icon, contentDescription = tab.label, tint = contentColor)
            }
        } else {
            Icon(tab.icon, contentDescription = tab.label, tint = contentColor)
        }
        // v1.0.1 tab-label choice: always / selected only / icons only.
        // The contentDescription always carries the label either way.
        val showLabel = when (labelStyle) {
            app.xreader.data.library.TabLabelStyle.ALWAYS -> true
            app.xreader.data.library.TabLabelStyle.SELECTED_ONLY -> selected
            app.xreader.data.library.TabLabelStyle.ICONS_ONLY -> false
        }
        if (showLabel) {
            Text(
                tab.label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}
